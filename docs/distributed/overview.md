# Lincheck for distributed algorithms.
This Lincheck extension provides an ability to test the prototypes of distributed algorithms implemented using the internal Lincheck API.
## Example
Let's consider the simple example of distributed key-value storage with one server and multiple clients. 
Client has two types of operations `fun get(key: Int): Int?` and `put(key: Int, value: Int): Int?`. The operations are executed on the server.

