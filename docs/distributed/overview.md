# Lincheck for distributed algorithms.

This Lincheck extension provides an ability to test the prototypes of distributed algorithms implemented using the
internal Lincheck API.

## Algorithm implementation

To be able to test the distributed algorithm, you have to implement it using the provided API. Each node in the system
is represented by the instance of the class implemented by the user. The node class should implement `Node` interface
and take `NodeEnvironment` as only constructor parameter. The `Node` and `NodeEnvironment` are parameterized by generic
type `Message`.

### Operations

### Interface `Node`

```kotlin
interface Node<Message> {
    fun onMessage(message: Message, sender: Int)
    ...
}
```

While implementing `Node` interface user should implement `onMessage` which will be called when `message` from node
with `id` sender arrives (more information about nodes' ids see below). This method defines how to react on message.

There are also over methods which can be optionally overridden (by default they do nothing):

1. `onStart` is called before running operations (FIXME)
2. `onScenarioFinished` is called after all operations from scenario for this node are completed. If the node doesn't
   have any operations to execute, the method is not called.
3. `recover` is called when the node restarts after crash (see below).
4. `onNodeUnavailable(x)` is called when the node `x` crashes or becomes unreachable because of a network partition. It
   can be used as an alternative to the failure detector (
   FIXME switch off only event logging).
5. `stateRepresentation` returns the string representation of the node's state. Could be used for debugging purposes (
   see Events).

### NodeEnvironment

The `NodeEnvironment` is passed to each node as a constructor parameter and is used for sending messages between nodes
and other specific purposes. 

To get information about nodes in the system, use:

* `id: Int` returns id of this node. The nodes are numbered from 0 until the total number of nodes. Ids are passed
  to `Node::onMessage` and `NodeEnvironment::send` and helps to distinguish nodes from each other.
* `nodes: Int` returns the total number in the system.
* `getIds<NodeType>(): List<Int>` returns the ids of nodes for the specified type, when there are multiple types of
  nodes in the system.

```kotlin
fun operation() {
    ...
    val server = env.getIds<Server>[0]
    env.send(msg, server)
}
```

To send messages between nodes:

* `send(message: Message, receiver: Int)` sends `message` to the node with id `receiver`.
* `broadcast(message: Message, skipItself: Boolean = true)` 
* `broadcastToGroup<NodeType>(message: Message, skipItself: Boolean = true)`



## Example

Let's consider the simple example of distributed key-value storage with one server and multiple clients. Client has two
types of operations `fun get(key: Int): Int?` and `put(key: Int, value: Int): Int?`. The operations are executed on the
server.

