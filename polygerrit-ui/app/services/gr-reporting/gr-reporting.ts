/**
 * @license
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {NumericChangeId} from '../../types/common';
import {EventDetails} from '../../api/reporting';

export type EventValue = string | number | {error?: Error};

export interface Timer {
  reset(): this;
  end(): this;
  withMaximum(maximum: number): this;
}

export interface ReportingService {
  reporter(
    type: string,
    category: string,
    eventName: string,
    eventValue?: EventValue,
    eventDetails?: EventDetails,
    opt_noLog?: boolean
  ): void;

  appStarted(): void;
  onVisibilityChange(): void;
  beforeLocationChanged(): void;
  locationChanged(page: string): void;
  dashboardDisplayed(): void;
  changeDisplayed(): void;
  changeFullyLoaded(): void;
  diffViewDisplayed(): void;
  diffViewFullyLoaded(): void;
  diffViewContentDisplayed(): void;
  fileListDisplayed(): void;
  reportExtension(name: string): void;
  pluginLoaded(name: string): void;
  pluginsLoaded(pluginsList?: string[]): void;
  error(err: unknown, reporter?: string, details?: EventDetails): void;
  /**
   * Reset named timer.
   */
  time(name: string): void;
  /**
   * Finish named timer and report it to server.
   */
  timeEnd(name: string, eventDetails?: EventDetails): void;
  /**
   * Reports just line timeEnd, but additionally reports an average given a
   * denominator and a separate reporting name for the average.
   *
   * @param name Timing name.
   * @param averageName Average timing name.
   * @param denominator Number by which to divide the total to
   *     compute the average.
   */
  timeEndWithAverage(
    name: string,
    averageName: string,
    denominator: number
  ): void;
  /**
   * Get a timer object for reporting a user timing. The start time will be
   * the time that the object has been created, and the end time will be the
   * time that the "end" method is called on the object.
   */
  getTimer(name: string): Timer;
  /**
   * Log timing information for an RPC.
   *
   * @param anonymizedUrl The URL of the RPC with tokens obfuscated.
   * @param elapsed The time elapsed of the RPC.
   */
  reportRpcTiming(anonymizedUrl: string, elapsed: number): void;
  reportLifeCycle(eventName: string, details?: EventDetails): void;

  /**
   * Use this method, if you want to check/count how often a certain code path
   * is executed. For example you can use this method to prove that certain code
   * paths are dead: Add reportExecution(), check the logs a week later, then
   * safely remove the coe.
   *
   * Every execution is only reported once per session.
   */
  reportExecution(id: string, details: EventDetails): void;
  reportInteraction(eventName: string, details?: EventDetails): void;
  /**
   * A draft interaction was started. Update the time-between-draft-actions
   * timer.
   */
  recordDraftInteraction(): void;
  reportErrorDialog(message: string): void;
  setRepoName(repoName: string): void;
  setChangeId(changeId: NumericChangeId): void;
}
