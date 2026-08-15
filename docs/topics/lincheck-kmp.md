[//]: # (title: Lincheck in Kotlin Multiplatform projects)
[//]: # (description: Learn how to set up Lincheck tests in multiplatform projects.)

Lincheck can be used with [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform/quickstart.html) 
to test the code targeting the JVM. This article will guide you through setting up Lincheck tests in a Kotlin 
Multiplatform project.

You will:
* Set up your project to run Lincheck tests for targets using the JVM.
* Create classes for counter data structures shared between all targets.
* Create tests for the counters shared between all targets and tests specific to the JVM.
* Run the tests.

## Set up a project

1. Create a [new Kotlin Multiplatform project](https://kotlinlang.org/docs/multiplatform/quickstart.html) in IntelliJ IDEA. When creating a project, make sure that:
   * The **Include tests** checkbox is checked.
   * Your project has a JVM-based (a server, desktop, or Android) target. You can verify that
     your code compiles for the JVM by checking the `shared/build.gradle.kts` or `core/build.gradle.kts` file:

     ```kotlin
     kotlin { 
         // ...
          
         jvm()
         
         // ...
         
         // If you have an Android target, the use of JVM is
         // configured in the `androidLibrary` section
         androidLibrary {
             // ...
             
             compilerOptions {
                 jvmTarget = JvmTarget.JVM_11
             }
         
         // ...
         
         }
     }
     ```
   
   > **Server/Desktop + Android** and **Server/Desktop + Android + iOS** projects
   > 
   > Kotlin currently doesn't support [sharing the code between JVM and Android targets](https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html#manual-configuration).
   > You will not be able to create shared tests for your platforms, but you can still test the code with Lincheck
   > using [platform-specific tests](https://kotlinlang.org/docs/multiplatform/multiplatform-run-tests.html#add-platform-specific-tests).
   >
   > For more information about the general test setup for Lincheck, read the 
   > [Getting started with Lincheck](lincheck-getting-started.md) article. 
   {style="warning"}

2. Set up the tests folder and Lincheck dependency according to the target platforms in your project:
   * [**Desktop/Android + iOS**](#server-or-desktop-or-android-ios)
   * [**Server + iOS, Server + Desktop**, **Server + Desktop + iOS**](#server-desktop-android-ios)

### Desktop/Android + iOS {id="server-or-desktop-or-android-ios"}

The directory for the platform-specific tests is created by the Kotlin Multiplatform plugin automatically.
For example, for a desktop target it is `shared/src/jvmTest/kotlin`.

Add a Lincheck dependency to the platform-specific [test source set](https://kotlinlang.org/docs/multiplatform/multiplatform-add-dependencies.html#kotlinx-libraries) and a `kotlin.test` dependency to the 
`commonTest` source set:

<tabs>
<tab id="desktop" title="Desktop">
    <code-block lang="Kotlin">
    // shared/build.gradle.kts
    kotlin {
        sourceSets {
            commonTest.dependencies {
                implementation(libs.kotlin.test)
            }
            jvmTest {
                implementation("org.jetbrains.lincheck:lincheck:%lincheckVersion%")
            }
        }
    }
    </code-block>
</tab>
<tab id="android" title="Android">
    <code-block lang="Kotlin">
    // shared/build.gradle.kts
    kotlin {
        sourceSets {
            commonTest.dependencies {
                implementation(libs.kotlin.test)
            }
            getByName("androidHostTest").dependencies {
                implementation("org.jetbrains.lincheck:lincheck:%lincheckVersion%")
            }
        }
    }
    </code-block>
</tab>
</tabs>

### Server + iOS, Server + Desktop, Server + Desktop + iOS {id="server-desktop-android-ios"}

1. Create a `core/src/jvmTest/kotlin` directory. Gradle will run the tests
   in this directory for all platforms that use the JVM.

   Projects with a desktop target also have a `shared/src/jvmTest/kotlin` directory which you can use instead.
   However, it is recommended to place the code relevant to both the server and desktop/iOS targets in the `core` 
   directory. 

   If you have a desktop target and create a `core/src/jvmTest/kotlin` directory, delete the
   `shared/src/jvmTest/kotlin` directory.
2. Add a Lincheck dependency to the `jvmTest` source set and a `kotlin.test` dependency to the
   `commonTest` source set:

   ```kotlin
   // core/build.gradle.kts
   kotlin {
       // ...
       sourceSets {
           // ...
           commonTest.dependencies {
               implementation(libs.kotlin.test)
           }
           jvmTest.dependencies {
               implementation("org.jetbrains.lincheck:lincheck:%lincheckVersion%")
           }
       }
   }
   ```

## Create shared classes

Create classes implementing counter data structures that are shared between all targets:

1. Create an `UnsafeCounter.kt` file in the `core/src/commonMain` or `shared/src/commonMain` directory:

   ```kotlin
   ```
   { src="kotlinx-lincheck/UnsafeCounterTests.kt" include-symbol="UnsafeCounter" }

2. Create a `SafeCounter.kt` file in the same directory:

   ```kotlin
   ```
   { src="kotlinx-lincheck/SafeCounterTests.kt" include-lines="5,6,9,10-17" }

## Write shared tests

Write tests that are [run for all targets](https://kotlinlang.org/docs/multiplatform/multiplatform-run-tests.html#add-tests):

1. Create an `UnsafeCounterTest.kt` file in the `core/src/commonTest` or `shared/src/commonTest` directory:
   
   ```kotlin
   ```
   { src="kotlinx-lincheck/UnsafeCounterTests.kt" include-lines="5,6,7,16-24" }

2. Create a `SafeCounterTest.kt` file in the same directory:

   ```kotlin
   ```
   { src="kotlinx-lincheck/SafeCounterTests.kt" include-lines="7,8,9,19-27" }
   
## Write JVM-specific tests

Write tests that are [only run for platforms targeting the JVM](https://kotlinlang.org/docs/multiplatform/multiplatform-run-tests.html#add-platform-specific-tests):

1. Create an `UnsafeCounterConcurrentTest.kt` file in the [test directory for JVM-specific tests](#set-up-a-project):

   * Android + iOS – `shared/src/androidHostTest/kotlin`
   * Desktop + iOS – `shared/src/jvmTest/kotlin`
   * Any project with a server target – `core/src/jvmTest/kotlin`

   ```kotlin
   ```
   { src="kotlinx-lincheck/UnsafeCounterTests.kt" include-lines="3,4,5,7,26-39" }

2. Create a `SafeCounterConcurrentTest.kt` file in the same directory:

   ```kotlin
   ```
   { src="kotlinx-lincheck/SafeCounterTests.kt" include-lines="3,4,7,9,29-42" }

## Run the tests

At this point, your project should have the shared implementation of the counter data structures, the shared tests,
and the JVM-specific tests for counters. The directory structure of your project should look like this:

<tabs>
<tab title="Android + iOS">
   <img src="android-ios-structure.png" 
        alt="A screenshot of the project file structure for the Android and iOS platforms."
        width="300"/>
</tab>
<tab title="Server + iOS">
   <img src="server-ios-structure.png" 
        alt="A screenshot of the project file structure for the server and iOS platforms."
        width="300"/>
</tab>
<tab title="Desktop + iOS">
   <img src="desktop-ios-structure.png" 
        alt="A screenshot of the project file structure for the desktop and iOS platforms."
        width="300"/>
</tab>
<tab title="Server + Desktop + iOS">
   <img src="server-desktop-ios-structure.png" 
        alt="A screenshot of the project file structure for the server, desktop, and iOS platforms."
        width="300"/>
</tab>
</tabs>

You can run individual tests from the context menu, using the shortcut, or by running a Gradle task. 
If you run the `allTests` Gradle task, every test in your project will be executed with the corresponding test runner:

![A screenshot of the Gradle plugin in IntelliJ IDEA with the **Tasks | Verification | allTests** task highlighted.](gradle-all-tests.png){width=300}

For example, the JVM-specific tests are executed with the JVM test runner:

![A screenshot of the test execution report. The Lincheck tests are marked with a `jvm` tag.](tests-executed-with-jvm.png){width=300}

## Support for platforms other than the JVM

Lincheck is unlikely to support other platforms in the future.

Lincheck relies heavily on JVM for [bytecode manipulation](lincheck-testing-strategies.md#model-checking), 
which makes model checking challenging to implement on other platforms. Moreover, using model checking for multiple 
platforms at the same time provides little value because Lincheck tests the code in a deterministic sandbox environment, 
and the results of the tests will be the same regardless of the platform.

## See also

* Learn more about [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform/get-started.html)
* Explore [Lincheck documentation](lincheck-getting-started.md)