### What is it

This tool makes a try to find violation of linearizability on concurrent data structure.

### How to use it

Consider a queue with interface:

```
public interface Queue {
    public void put(int x) throws QueueFullException;
    public int get() throws QueueEmptyException;
}
```

To test such queue we should declare special class with annotations:

```
@CTest(iter = 300, actorsPerThread = {"1:3", "1:3"})
@CTest(iter = 300, actorsPerThread = {"1:3", "1:3", "1:3"})
public class QueueTest {
    private Queue queue;

    @Reload
    public void reload() {
        queue = new NonBlockingConcurrentQueue(10);
    }

    @Operation(args = {"1:10"})
    public void put(Result res, Object[] args) throws Exception {
        Integer x = (Integer) args[0];
        queue.put(x);
        res.setVoid();
    }

    @Operation(args = {})
    public void get(Result res, Object[] args) throws Exception {
        Integer value = queue.get();
        res.setValue(value);
    }

    @Test
    public void test() throws Exception {
        Checker checker = new Checker();
        assertTrue(checker.checkAnnotated(new QueueTest()));
    }
}

```

Let's look at annotations.

`@CTest(iter = 300, actorsPerThread = {"1:3", "1:3"})`

This annotation define a class of tests. There are 300 test cases.
Each test case consists of two threads with some operations on data structure (from 1 to 3).

`@CTest(iter = 300, actorsPerThread = {"1:3", "1:3", "1:3"})`

Same class of tests but with three threads.

Then we should define some methods in class.

Method with annotation `@Reload` should reinitialize data structure.

And for each testing method of data structure we define wrapper method with annotation ```@Operation```.
Field `args` define range of input arguments (Only int type supported for now). They would be placed in args array.
In this method we should take them from args arrays, invoke method of data structure and put result in `res` variable.

After that we create a Checker instance and invoke `checkAnnotated` on it with instance of our class.

It's useful to define JUnit test such that

```
@Test
public void test() throws Exception {
    Checker checker = new Checker();
    assertTrue(checker.checkAnnotated(new QueueTest()));
}
```

If data stucture looks like linearizable `checkAnnotated` returns `true`. Otherwise return `false` and the test case would be printed.

Example of output:

```
// test case
// each line describes operations in thread
// (number prefix is for distinguishing methods with same name and arguments)
Thread configuration:
[0_put(5), 1_get()]
[2_get()]
```


```
// possible sequential histories
[0_put(5), 1_get(), 2_get()]
[0_put(5), 2_get(), 1_get()]
[2_get(), 0_put(5), 1_get()]
```

```
// possible results of execution of sequential histories
// result with index `i` is for operation with the same index in prefix
[VOID, 5, QueueEmptyException]
[VOID, QueueEmptyException, 5]
```

```
// discovered result which we can't expain with sequential histories
Unexpected result:
[VOID, 5, 5]
```


So there is an error. It's incorrect behaviour of concurrent queue.
```
P: put(5) {void}; get() {5};
Q: get() {5};
```

Also you may take a look to another examples of testing contained in `src/test/java` (for data structure from `src/main/java/com/devexperts/dxlab/lincheck/tests/custom`)