# Contributing Guidelines

There are two main ways to contribute to the project &mdash; submitting issues and submitting
fixes/changes/improvements via pull requests.

## Submitting issues

Both bug reports and feature requests are welcome.
Submit issues [here](https://github.com/Kotlin/kotlinx-lincheck/issues).

* Search for existing issues to avoid reporting duplicates.
* When submitting a bug report:
    * Test it against the most recently released version. It might have been already fixed.
    * Include the code that reproduces the problem. Provide the complete reproducer code, yet minimize it as much as possible.
    * However, don't put off reporting any weird or rarely appearing issues just because you cannot consistently
      reproduce them.
    * If the bug is in behavior, then explain what behavior you've expected and what you've got.
* When submitting a feature request:
    * Explain why you need the feature &mdash; what's your use-case, what's your domain.
    * Explaining the problem you face is more important than suggesting a solution.
      Report your problem even if you don't have any proposed solution.
    * If there is an alternative way to do what you need, then show the code of the alternative.

## Submitting PRs

We love PRs. Submit PRs [here](https://github.com/Kotlin/kotlinx-lincheck/pulls).
However, please keep in mind that maintainers will have to support the resulting code of the project,
so do familiarize yourself with the following guidelines.

* All development (both new features and bug fixes) is performed in the `develop` branch.
    * The `master` branch always contains sources of the most recently released version.
    * Base PRs against the `develop` branch.
    * The `develop` branch is pushed to the `master` branch during release.
    * Documentation in markdown files can be updated directly in the `master` branch,
      unless the documentation is in the source code.
* If you fix documentation:
    * If you plan extensive rewrites/additions to the docs, then please create an issue
      to coordinate the work in advance.
* If you make any code changes:
    * Follow the [Kotlin Coding Conventions](https://kotlinlang.org/docs/reference/coding-conventions.html).
      Use 4 spaces for indentation.
    * [Build the project](#building) to make sure it all works and passes the tests.
* If you fix a bug:
    * Write a test that reproduces the bug.
    * Fixes without tests are accepted only in exceptional circumstances if it can be shown that writing the
      corresponding test is too hard or otherwise impractical.
* If you introduce any new public APIs:
    * Comment on the existing issue if you want to work on it or create one beforehand.
      Ensure that the issue not only describes a problem, but also describes a solution that had received a positive feedback. Propose a solution if there isn't any.
      PRs with new API, but without a corresponding issue with a positive feedback about the proposed implementation are unlikely to
      be approved or reviewed.
    * All new APIs must come with documentation and tests.
    * If you plan large API additions, then please start by submitting an issue with the proposed API design.


## Building
This framework is built with Gradle.

* Run `./gradlew build` to build. It also runs all the tests.

You can import this project into IDEA, but you have to delegate build actions
to Gradle (in Preferences -> Build, Execution, Deployment -> Build Tools -> Gradle -> Runner)

### Environment requirements

* JDK >= 11 referred to by the `JAVA_HOME` environment variable.

## Releases

* Full release procedure checklist is [here](RELEASE.md).