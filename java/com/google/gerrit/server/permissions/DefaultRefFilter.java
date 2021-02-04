// Copyright (C) 2010 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.permissions;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.flogger.LazyArgs.lazy;
import static com.google.gerrit.entities.RefNames.REFS_CONFIG;
import static java.util.stream.Collectors.toCollection;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.metrics.Counter0;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.git.SearchingChangeCacheImpl;
import com.google.gerrit.server.git.TagCache;
import com.google.gerrit.server.git.TagMatcher;
import com.google.gerrit.server.logging.TraceContext;
import com.google.gerrit.server.logging.TraceContext.TraceTimer;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.notedb.ChangeNotes.Factory.ChangeNotesResult;
import com.google.gerrit.server.permissions.PermissionBackend.RefFilterOptions;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Ref.Storage;
import org.eclipse.jgit.lib.Repository;

class DefaultRefFilter {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  interface Factory {
    DefaultRefFilter create(ProjectControl projectControl);
  }

  private final TagCache tagCache;
  private final ChangeNotes.Factory changeNotesFactory;
  @Nullable private final SearchingChangeCacheImpl changeCache;
  private final PermissionBackend permissionBackend;
  private final RefVisibilityControl refVisibilityControl;
  private final ProjectControl projectControl;
  private final CurrentUser user;
  private final ProjectState projectState;
  private final PermissionBackend.ForProject permissionBackendForProject;
  private final Counter0 fullFilterCount;
  private final Counter0 skipFilterCount;
  private final boolean skipFullRefEvaluationIfAllRefsAreVisible;

  private Map<Change.Id, ChangeNotes> visibleChanges;

  @Inject
  DefaultRefFilter(
      TagCache tagCache,
      ChangeNotes.Factory changeNotesFactory,
      @Nullable SearchingChangeCacheImpl changeCache,
      PermissionBackend permissionBackend,
      RefVisibilityControl refVisibilityControl,
      @GerritServerConfig Config config,
      MetricMaker metricMaker,
      @Assisted ProjectControl projectControl) {
    this.tagCache = tagCache;
    this.changeNotesFactory = changeNotesFactory;
    this.changeCache = changeCache;
    this.permissionBackend = permissionBackend;
    this.refVisibilityControl = refVisibilityControl;
    this.skipFullRefEvaluationIfAllRefsAreVisible =
        config.getBoolean("auth", "skipFullRefEvaluationIfAllRefsAreVisible", true);
    this.projectControl = projectControl;

    this.user = projectControl.getUser();
    this.projectState = projectControl.getProjectState();
    this.permissionBackendForProject =
        permissionBackend.user(user).project(projectState.getNameKey());
    this.fullFilterCount =
        metricMaker.newCounter(
            "permissions/ref_filter/full_filter_count",
            new Description("Rate of full ref filter operations").setRate());
    this.skipFilterCount =
        metricMaker.newCounter(
            "permissions/ref_filter/skip_filter_count",
            new Description(
                    "Rate of ref filter operations where we skip full evaluation"
                        + " because the user can read all refs")
                .setRate());
  }

  /** Filters given refs and tags by visibility. */
  Collection<Ref> filter(Collection<Ref> refs, Repository repo, RefFilterOptions opts)
      throws PermissionBackendException {
    logger.atFinest().log(
        "Filter refs for repository %s by visibility (options = %s, refs = %s)",
        projectState.getNameKey(), opts, refs);
    logger.atFinest().log("Calling user: %s", user.getLoggableName());
    logger.atFinest().log("Groups: %s", lazy(() -> user.getEffectiveGroups().getKnownGroups()));
    logger.atFinest().log(
        "auth.skipFullRefEvaluationIfAllRefsAreVisible = %s",
        skipFullRefEvaluationIfAllRefsAreVisible);
    logger.atFinest().log(
        "Project state %s permits read = %s",
        projectState.getProject().getState(), projectState.statePermitsRead());

    // If we anyway always return all available (most recent) changes in the change index and cache,
    // we shouldn't care about refs/changes.
    if (opts.returnMostRecentRefChanges()) {
      refs =
          refs.stream()
              .filter(r -> !RefNames.isRefsChanges(r.getName()))
              .collect(Collectors.toList());
    }

    // See if we can get away with a single, cheap ref evaluation.
    if (refs.size() == 1) {
      String refName = Iterables.getOnlyElement(refs).getName();
      if (opts.filterMeta() && isMetadata(refName)) {
        logger.atFinest().log("Filter out metadata ref %s", refName);
        return ImmutableList.of();
      }
      if (RefNames.isRefsChanges(refName) && !opts.returnMostRecentRefChanges()) {
        boolean isChangeRefVisisble = canSeeSingleChangeRef(refName);
        if (isChangeRefVisisble) {
          logger.atFinest().log("Change ref %s is visible", refName);
          return refs;
        }
        logger.atFinest().log("Filter out non-visible change ref %s", refName);
        return ImmutableList.of();
      }
    }

    // Perform an initial ref filtering with all the refs the caller asked for. If we find tags that
    // we have to investigate separately (deferred tags) then perform a reachability check starting
    // from all visible branches (refs/heads/*).
    Result initialRefFilter = filterRefs(new ArrayList<>(refs), repo, opts);
    List<Ref> visibleRefs = initialRefFilter.visibleRefs();
    if (!initialRefFilter.deferredTags().isEmpty()) {
      try (TraceTimer traceTimer = TraceContext.newTimer("Check visibility of deferred tags")) {
        Result allVisibleBranches = filterRefs(getTaggableRefs(repo), repo, opts);
        checkState(
            allVisibleBranches.deferredTags().isEmpty(),
            "unexpected tags found when filtering refs/heads/* "
                + allVisibleBranches.deferredTags());

        TagMatcher tags =
            tagCache
                .get(projectState.getNameKey())
                .matcher(tagCache, repo, allVisibleBranches.visibleRefs());
        for (Ref tag : initialRefFilter.deferredTags()) {
          try {
            if (tags.isReachable(tag)) {
              logger.atFinest().log("Include reachable tag %s", tag.getName());
              visibleRefs.add(tag);
            } else {
              logger.atFinest().log("Filter out non-reachable tag %s", tag.getName());
            }
          } catch (IOException e) {
            throw new PermissionBackendException(e);
          }
        }
      }
    }

    if (opts.returnMostRecentRefChanges()) {
      visibleChanges(repo).values().stream()
          .forEach(n -> addAllChangeAndPatchsetRefs(visibleRefs, n));
    }

    logger.atFinest().log("visible refs = %s", visibleRefs);
    return visibleRefs;
  }

  private void addAllChangeAndPatchsetRefs(Collection<Ref> refs, ChangeNotes changeNotes) {
    refs.add(
        new ObjectIdRef.PeeledNonTag(
            Storage.PACKED,
            RefNames.changeMetaRef(changeNotes.getChangeId()),
            changeNotes.getMetaId()));
    changeNotes
        .getPatchSets()
        .values()
        .forEach(
            p ->
                refs.add(
                    new ObjectIdRef.PeeledNonTag(
                        Storage.PACKED, RefNames.patchSetRef(p.id()), p.commitId())));
  }

  /**
   * Filters refs by visibility. Returns tags where visibility can't be trivially computed
   * separately for later rev-walk-based visibility computation. Tags where visibility is trivial to
   * compute will be returned as part of {@link Result#visibleRefs()}.
   */
  Result filterRefs(List<Ref> refs, Repository repo, RefFilterOptions opts)
      throws PermissionBackendException {
    logger.atFinest().log("Filter refs (refs = %s)", refs);

    // TODO(hiesel): Remove when optimization is done.
    boolean hasReadOnRefsStar =
        checkProjectPermission(permissionBackendForProject, ProjectPermission.READ);
    logger.atFinest().log("User has READ on refs/* = %s", hasReadOnRefsStar);
    if (skipFullRefEvaluationIfAllRefsAreVisible && !projectState.isAllUsers()) {
      if (projectState.statePermitsRead() && hasReadOnRefsStar) {
        skipFilterCount.increment();
        logger.atFinest().log(
            "Fast path, all refs are visible because user has READ on refs/*: %s", refs);
        return new AutoValue_DefaultRefFilter_Result(refs, ImmutableList.of());
      } else if (projectControl.allRefsAreVisible(ImmutableSet.of(RefNames.REFS_CONFIG))) {
        skipFilterCount.increment();
        refs = fastHideRefsMetaConfig(refs);
        logger.atFinest().log(
            "Fast path, all refs except %s are visible: %s", RefNames.REFS_CONFIG, refs);
        return new AutoValue_DefaultRefFilter_Result(refs, ImmutableList.of());
      }
    }
    logger.atFinest().log("Doing full ref filtering");
    fullFilterCount.increment();

    boolean hasAccessDatabase =
        permissionBackend
            .user(projectControl.getUser())
            .testOrFalse(GlobalPermission.ACCESS_DATABASE);
    List<Ref> resultRefs = new ArrayList<>(refs.size());
    List<Ref> deferredTags = new ArrayList<>();
    for (Ref ref : refs) {
      String refName = ref.getName();
      Change.Id changeId;
      if (opts.filterMeta() && isMetadata(refName)) {
        logger.atFinest().log("Filter out metadata ref %s", refName);
      } else if (isTag(ref)) {
        if (hasReadOnRefsStar) {
          // The user has READ on refs/* with no effective block permission. This is the broadest
          // permission one can assign. There is no way to grant access to (specific) tags in
          // Gerrit,
          // so we have to assume that these users can see all tags because there could be tags that
          // aren't reachable by any visible ref while the user can see all non-Gerrit refs. This
          // matches Gerrit's historic behavior.
          // This makes it so that these users could see commits that they can't see otherwise
          // (e.g. a private change ref) if a tag was attached to it. Tags are meant to be used on
          // the regular Git tree that users interact with, not on any of the Gerrit trees, so this
          // is a negligible risk.
          logger.atFinest().log("Include tag ref %s because user has read on refs/*", refName);
          resultRefs.add(ref);
        } else {
          // If its a tag, consider it later.
          if (ref.getObjectId() != null) {
            logger.atFinest().log("Defer tag ref %s", refName);
            deferredTags.add(ref);
          } else {
            logger.atFinest().log("Filter out tag ref %s that is not a tag", refName);
          }
        }
      } else if ((changeId = Change.Id.fromRef(refName)) != null) {
        // This is a mere performance optimization. RefVisibilityControl could determine the
        // visibility of these refs just fine. But instead, we use highly-optimized logic that
        // looks only on the available changes in the change index and cache (which are the
        // most recent changes).
        if (hasAccessDatabase) {
          resultRefs.add(ref);
        } else if (!visible(repo, changeId)) {
          logger.atFinest().log("Filter out invisible change ref %s", refName);
        } else if (RefNames.isRefsEdit(refName) && !visibleEdit(repo, refName)) {
          logger.atFinest().log("Filter out invisible change edit ref %s", refName);
        } else {
          // Change is visible
          resultRefs.add(ref);
        }
      } else if (refVisibilityControl.isVisible(projectControl, ref.getLeaf().getName())) {
        resultRefs.add(ref);
      }
    }
    Result result = new AutoValue_DefaultRefFilter_Result(resultRefs, deferredTags);
    logger.atFinest().log("Result of ref filtering = %s", result);
    return result;
  }

  /**
   * Returns all refs tag we regard as starting points for reachability computation for tags. In
   * general, these are all refs not managed by Gerrit excluding symbolic refs and tags.
   *
   * <p>We exclude symbolic refs because their target will be included and this will suffice for
   * computing reachability.
   */
  private static List<Ref> getTaggableRefs(Repository repo) throws PermissionBackendException {
    try {
      List<Ref> allRefs = repo.getRefDatabase().getRefs();
      return allRefs.stream()
          .filter(
              r ->
                  !RefNames.isGerritRef(r.getName())
                      && !r.getName().startsWith(RefNames.REFS_TAGS)
                      && !r.isSymbolic()
                      && !r.getName().equals(RefNames.REFS_CONFIG))
          .collect(Collectors.toList());
    } catch (IOException e) {
      throw new PermissionBackendException(e);
    }
  }

  private List<Ref> fastHideRefsMetaConfig(List<Ref> refs) throws PermissionBackendException {
    if (!canReadRef(REFS_CONFIG)) {
      return refs.stream()
          .filter(r -> !r.getName().equals(REFS_CONFIG))
          .collect(toCollection(() -> new ArrayList<>(refs.size())));
    }
    return refs;
  }

  private boolean visible(Repository repo, Change.Id changeId) throws PermissionBackendException {
    return visibleChanges(repo).containsKey(changeId);
  }

  private Map<Change.Id, ChangeNotes> visibleChanges(Repository repo)
      throws PermissionBackendException {
    if (visibleChanges == null) {
      if (changeCache == null) {
        visibleChanges = visibleChangesByScan(repo);
      } else {
        visibleChanges = visibleChangesBySearch();
      }
      logger.atFinest().log("Visible changes: %s", visibleChanges.keySet());
    }
    return visibleChanges;
  }

  private boolean visibleEdit(Repository repo, String name) throws PermissionBackendException {
    Change.Id id = Change.Id.fromEditRefPart(name);
    if (id == null) {
      logger.atWarning().log("Couldn't extract change ID from edit ref %s", name);
      return false;
    }

    if (user.isIdentifiedUser()
        && name.startsWith(RefNames.refsEditPrefix(user.asIdentifiedUser().getAccountId()))
        && visible(repo, id)) {
      logger.atFinest().log("Own change edit ref is visible: %s", name);
      return true;
    }

    if (visible(repo, id)) {
      try {
        // Default to READ_PRIVATE_CHANGES as there is no special permission for reading edits.
        permissionBackendForProject
            .ref(visibleChanges(repo).get(id).getChange().getDest().branch())
            .check(RefPermission.READ_PRIVATE_CHANGES);
        logger.atFinest().log("Foreign change edit ref is visible: %s", name);
        return true;
      } catch (AuthException e) {
        logger.atFinest().log("Foreign change edit ref is not visible: %s", name);
        return false;
      }
    }

    logger.atFinest().log("Change %d of change edit ref %s is not visible", id.get(), name);
    return false;
  }

  private Map<Change.Id, ChangeNotes> visibleChangesBySearch() throws PermissionBackendException {
    Project.NameKey project = projectState.getNameKey();
    try {
      Map<Change.Id, ChangeNotes> visibleChanges = new HashMap<>();
      for (ChangeData cd : changeCache.getChangeData(project)) {
        if (!projectState.statePermitsRead()) {
          continue;
        }
        try {
          permissionBackendForProject.change(cd).check(ChangePermission.READ);
          visibleChanges.put(cd.getId(), cd.notes());
        } catch (AuthException e) {
          // Do nothing.
        }
      }
      return visibleChanges;
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log(
          "Cannot load changes for project %s, assuming no changes are visible", project);
      return Collections.emptyMap();
    }
  }

  private Map<Change.Id, ChangeNotes> visibleChangesByScan(Repository repo)
      throws PermissionBackendException {
    Project.NameKey p = projectState.getNameKey();
    ImmutableList<ChangeNotesResult> changes;
    try {
      changes = changeNotesFactory.scan(repo, p).collect(toImmutableList());
    } catch (IOException e) {
      logger.atSevere().withCause(e).log(
          "Cannot load changes for project %s, assuming no changes are visible", p);
      return Collections.emptyMap();
    }

    Map<Change.Id, ChangeNotes> result = Maps.newHashMapWithExpectedSize(changes.size());
    for (ChangeNotesResult notesResult : changes) {
      ChangeNotes notes = toNotes(notesResult);
      if (notes != null) {
        result.put(notes.getChangeId(), notes);
      }
    }
    return result;
  }

  @Nullable
  private ChangeNotes toNotes(ChangeNotesResult r) throws PermissionBackendException {
    if (r.error().isPresent()) {
      logger.atWarning().withCause(r.error().get()).log(
          "Failed to load change %s in %s", r.id(), projectState.getName());
      return null;
    }

    if (!projectState.statePermitsRead()) {
      return null;
    }

    try {
      permissionBackendForProject.change(r.notes()).check(ChangePermission.READ);
      return r.notes();
    } catch (AuthException e) {
      // Skip.
    }
    return null;
  }

  private boolean isMetadata(String name) {
    boolean isMetaData = RefNames.isRefsChanges(name) || RefNames.isRefsEdit(name);
    logger.atFinest().log("ref %s is " + (isMetaData ? "" : "not ") + "a metadata ref", name);
    return isMetaData;
  }

  private static boolean isTag(Ref ref) {
    return ref.getLeaf().getName().startsWith(Constants.R_TAGS);
  }

  private boolean canReadRef(String ref) throws PermissionBackendException {
    try {
      permissionBackendForProject.ref(ref).check(RefPermission.READ);
    } catch (AuthException e) {
      return false;
    }
    return projectState.statePermitsRead();
  }

  private boolean checkProjectPermission(
      PermissionBackend.ForProject forProject, ProjectPermission perm)
      throws PermissionBackendException {
    try {
      forProject.check(perm);
    } catch (AuthException e) {
      return false;
    }
    return true;
  }

  /**
   * Returns true if the user can see the provided change ref. Uses NoteDb for evaluation, hence
   * does not suffer from the limitations documented in {@link SearchingChangeCacheImpl}.
   *
   * <p>This code lets users fetch changes that are not among the fraction of most recently modified
   * changes that {@link SearchingChangeCacheImpl} returns. This works only when Git Protocol v2
   * with refs-in-wants is used as that enables Gerrit to skip traditional advertisement of all
   * visible refs.
   */
  private boolean canSeeSingleChangeRef(String refName) throws PermissionBackendException {
    // We are treating just a single change ref. We are therefore not going through regular ref
    // filtering, but use NoteDb directly. This makes it so that we can always serve this ref
    // even if the change is not part of the set of most recent changes that
    // SearchingChangeCacheImpl returns.
    Change.Id cId = Change.Id.fromRef(refName);
    if (cId == null) {
      // The ref is not a valid change ref. Treat it as non-visible since it's not representing a
      // change.
      logger.atWarning().log("invalid change ref %s is not visible", refName);
      return false;
    }
    ChangeNotes notes;
    try {
      notes = changeNotesFactory.create(projectState.getNameKey(), cId);
    } catch (StorageException e) {
      throw new PermissionBackendException("can't construct change notes", e);
    }
    try {
      permissionBackendForProject.change(notes).check(ChangePermission.READ);
      return true;
    } catch (AuthException e) {
      return false;
    }
  }

  @AutoValue
  abstract static class Result {
    /** Subset of the refs passed into the computation that is visible to the user. */
    abstract List<Ref> visibleRefs();

    /**
     * List of tags where we couldn't figure out visibility in the first pass and need to do an
     * expensive ref walk.
     */
    abstract List<Ref> deferredTags();
  }
}
