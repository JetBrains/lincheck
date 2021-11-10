#Broadcast 
##Problem description
Imagine that you have to implement a mechanism for broadcasting messages to a group of nodes with the guarantees described below. 
1. The number of nodes in the system is constant, new nodes cannot be added. 
2. Nodes can crash during the execution. If the node crashed, it does not recover. The nodes which did not crash will be called __correct__. We assume that the majority of nodes are correct.
3. Nodes are sending messages to each other. We assume that the message is delivered to a final user when it is put into log.
4. The implementation of the broadcast should satisfy the following conditions:
   1. Each message should be delivered at most once.
   2. If the message `m` from node `s` was delivered, `m` was sent before by node `s`.
   3. If the correct node `p` has sent message `m`, message `m` should be delivered to the user by `p`.
   4. If message `m` was delivered by one node, it will be eventually delivered by all correct nodes.
   5. If some node sent message `m1` before `m2` all correct nodes which delivered `m2` should deliver `m1` before `m2`.
##Implementation details
