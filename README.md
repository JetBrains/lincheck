# Demo Lincheck project for ECOOP 2024
Testing Concurrent Algorithms with Lincheck and IntelliJ IDEA

### Setup
1. Install IntelliJ IDEA (Ultimate Edition)
2. Open `Plugins` screen 

![](./img/step_1.png) 

![](./img/step_2.png)

3. Search for the `Lincheck` plugin and install it 
![](./img/step_3.png)

### Application
1. Open the example test [SPSCMSQueueTest](./src/test/kotlin/org/jetbrains/ecoop24/SPSCQueueTest.kt).
2. Run the test. 
![](./img/run_test.png)
3. Wait for the test to fail, scroll the output and find the button. 
![](./img/button.png)
4. Press the button and wait for the debugger to start. 
![](./img/debugger.png)
5. Enjoy the debugging!



### Tests and examples
Run with debugger these tests:
* [Single-consumer single-producer concurrent queue test](./src/test/kotlin/org/jetbrains/ecoop24/SPSCQueueTest.kt)
* [Fetch-And-Add Queue test](./src/test/kotlin/org/jetbrains/ecoop24/FaaQueueTest.kt)
* [Concurrent Deque from JDK test](./src/test/kotlin/org/jetbrains/ecoop24/ConcurrentDequeTest.kt)

