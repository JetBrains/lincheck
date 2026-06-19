[//]: # (title: Lincheck in Kotlin Multiplatform projects)
[//]: # (description: Learn how to set up Lincheck tests in multiplatform projects)

Lincheck can be used with [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform/quickstart.html) for 
testing the code targeting the JVM.

> **Will Lincheck support other platforms in the future?**
>
> Lincheck relies heavily on JVM for [bytecode manipulation](lincheck-testing-strategies.md#model-checking) that is not 
> supported on other platforms. 
> 
> Implementing model checking for multiple targets is not only challenging, but also provides 
> little value. Model checking enables Lincheck to test the code in a deterministic 
> sandbox environment, which means that the results of the tests will be the same regardless 
> of the platform.
> 
{style="tip"}

This article will guide you through setting up Lincheck tests in a Kotlin Multiplatform project.

You will:
* Set up your project to run Lincheck tests for targets using the JVM.
* Create a `Counter` structure shared between all targets.
* Create tests for `Counter` shared between all targets and tests specific to the JVM.
* Run the tests.

## Set up a project

1. Create a [new Kotlin Multiplatform project](https://kotlinlang.org/docs/multiplatform/quickstart.html) in IntelliJ IDEA or open an existing one.

   Your project must have a JVM target, for example, a server, desktop, or an Android app. You can make sure that
   your code compiles for the JVM by checking the `shared/build.gradle.kts` or `core/build.gradle.kts` file:

   ```kotlin
   kotlin {
       // ...
       
       jvm()
       
       // ...
       
       // If you have an Android app, the use of JVM is
       // stated in the Android configuration section
       androidLibrary {
           // ...
   
           compilerOptions {
               jvmTarget = JvmTarget.JVM_11
           }
           
           // ...
       }
   }
   ```
   
2. Set up the tests folder and Lincheck dependency according to the target platforms in your project:
   * [**Desktop/Android + iOS**](#server-or-desktop-or-android-ios)
   * [**Server + iOS, Server + Desktop**, **Server + Desktop + iOS**](#server-desktop-android-ios)
   * **Server/Desktop + Android**, **Server/Desktop + Android + iOS** – Kotlin doesn't currently support [sharing a source 
     set for JVM + Android targets](https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html#manual-configuration). 

### Desktop/Android + iOS {id="server-or-desktop-or-android-ios"}

The directory for the platform-specific tests is created by the Kotlin Multiplatform plugin automatically.
For example, for a desktop target it is `shared/src/jvmTest`.

Add a Lincheck dependency to the [test source set](https://kotlinlang.org/docs/multiplatform/multiplatform-add-dependencies.html#kotlinx-libraries):

<tabs>
<tab id="desktop" title="Desktop">
    <code-block lang="Kotlin">
    // app/shared/build.gradle.kts
    kotlin {
        sourceSets {
            jvmTest {
                implementation("org.jetbrains.lincheck:lincheck:3.5")
            }
        }
    }
    </code-block>
</tab>
<tab id="android" title="Android">
    <code-block lang="Kotlin">
    // app/shared/build.gradle.kts
    kotlin {
        sourceSets {
            getByName("androidHostTest").dependencies {
                implementation("org.jetbrains.lincheck:lincheck:3.5")
            }
        }
    }
    </code-block>
</tab>
</tabs>

### Server + iOS, Server + Desktop, Server + Desktop + iOS {id="server-desktop-android-ios"}

1. Create a `core/src/jvmTest` directory. Gradle will run the tests
   in the `core/src/jvmTest` directory for all platforms that use the JVM.

   If your project has a `shared/src/jvmTest` directory, you can use it instead. However,
   it is recommended to place the code relevant to both the server and desktop/iOS apps in the `core`
   folder. 

   If your project already has a `shared/src/jvmTest` directory, and you create a `core/src/jvmTest`
   directory, delete the `shared/src/jvmTest` directory.
2. Add a Lincheck dependency to the `jvmTest` source set:

   ```kotlin
   // core/build.gradle.kts
   kotlin {
       // ...
       sourceSets {
           // ...
           jvmTest {
               dependencies {
                   implementation("org.jetbrains.kotlinx:lincheck:3.5")
               }
           }
       }
   }
   ```

## Create shared structures

Create structures shared between all targets regardless of the platform:

1. Create an `UnsafeCounter` structure in the `core/src/commonMain` or `shared/src/commonMain` directory:

   ```kotlin
   class UnsafeCounter {
      private var value: Int = 0
      
      fun inc() { 
          value++
      }
      
      fun get(): Int = value
   }
   ```

2. In the same directory, create a `SafeCounter` structure:

   ```kotlin
   @OptIn(ExperimentalAtomicApi::class)
   class SafeCounter {
       private val _value = AtomicInt(0)
   
       var value: Int
           get() = _value.load()
           set(newValue) {
               _value.store(newValue)
           }
   
       fun inc() {
           _value.addAndFetch(1)
       }
   
       fun get(): Int = _value.load()
   }
   ```

## Write shared tests

Write tests that are [run for all targets](https://kotlinlang.org/docs/multiplatform/multiplatform-run-tests.html#add-tests) 
regardless of the platform:

1. Create an `UnsafeCounterTest.kt` file in the `core/src/commonTest` or `shared/src/commonTest` directory:
   
   ```kotlin
   class UnsafeCounterTest {
      @Test
      fun testIncrement() {
         val counter = UnsafeCounter()
         assertEquals(0, counter.get(), "Initial value should be 0")
         counter.inc()
         assertEquals(1, counter.get(), "Value after one increment should be 1")
      }
   }
   ```

2. In the same directory, create a `SafeCounterTest.kt` file:

   ```kotlin
   class SafeCounterTest {
      @Test
      fun testIncrement() {
         val counter = SafeCounter()
         assertEquals(0, counter.get(), "Initial value should be 0")
         counter.inc()
         assertEquals(1, counter.get(), "Value after one increment should be 1")
      }
   }
   ```
   
## Write JVM-specific tests

Write tests that are [only run for platforms targeting the JVM](https://kotlinlang.org/docs/multiplatform/multiplatform-run-tests.html#add-platform-specific-tests):

1. In the [test directory for JVM-specific tests](#set-up-a-project), create an `UnsafeCounterConcurrentTest.kt` file:

   ```kotlin
   class UnsafeCounterConcurrentTest {
       private val c = UnsafeCounter()
   
       @Operation
       fun inc() = c.inc()
   
       @Operation
       fun get() = c.get()
   
       @Test
       fun modelCheckingTest() {
           ModelCheckingOptions().check(this::class)
       }
   }
   ```

2. In the same directory, create a `SafeCounterConcurrentTest.kt` file:

   ```kotlin
   class SafeCounterConcurrentTest {
       private val c = SafeCounter()
   
       @Operation
       fun inc() = c.inc()
   
       @Operation
       fun get() = c.get()
   
       @Test
       fun modelCheckingTest() {
           ModelCheckingOptions().check(this::class)
       }
   }
   ```

## Run the tests

At this point, your project should have the shared implementation of the `Counter` structure, the shared tests,
and the JVM-specific tests for `Counter`. The directory structure of your project should look like this:

<tabs>
<tab title="Server + Desktop + iOS">
   <img src="server-desktop-ios-structure.png" 
        alt="A screenshot of the project file structure for the server, desktop, and iOS platforms."
        width="300"/>
</tab>
<tab title="Server + iOS">
   <img src="server-ios-structure.png" 
        alt="A screenshot of the project file structure for the server and iOS platforms."
        width="300"/>
</tab>
<tab title="Android + iOS">
   <img src="android-ios-structure.png" 
        alt="A screenshot of the project file structure for the Android and iOS platforms."
        width="300"/>
</tab>
<tab title="Desktop + iOS">
   <img src="desktop-ios-structure.png" 
        alt="A screenshot of the project file structure for the desktop and iOS platforms."
        width="300"/>
</tab>
</tabs>

You can run individual tests from the context menu, using the shortcut, or by running a Gradle task. 
If you run the `allTests` Gradle task, every test in your project will be executed with the corresponding test runner:

![A screenshot of the Gradle plugin in IntelliJ IDEA with the **Tasks | Verification | allTests** task highlighted.](gradle-all-tests.png){width=300}

For example, the JVM-specific tests are executed with the JVM test runner:

![A screenshot of the test execution report. The Lincheck tests are marked with a `jvm` tag.](tests-executed-with-jvm.png){width=300}

## See also

* Learn more about [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform/get-started.html)
* Explore [Lincheck documentation](lincheck-getting-started.md)