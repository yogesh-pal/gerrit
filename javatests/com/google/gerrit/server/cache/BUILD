load("//tools/bzl:junit.bzl", "junit_tests")
load("//javatests/com/google/gerrit/acceptance:tests.bzl", "acceptance_tests")

junit_tests(
    name = "tests",
    srcs = glob(["*Test.java"]),
    deps = [
        "//java/com/google/gerrit/server",
        "//java/com/google/gerrit/testing:gerrit-test-util",
        "//javatests/com/google/gerrit/util/http/testutil",
        "//lib:junit",
        "//lib/truth",
        "//lib/truth:truth-java8-extension",
        "@servlet-api//jar",
    ],
)

acceptance_tests(
    srcs = glob(["*IT.java"]),
    group = "server_cache",
    labels = ["server"],
)
