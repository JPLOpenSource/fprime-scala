# F' Issues

## Many input ports, one queue

The fact that each component only has one input message queue, to which all input  ports are connected, can lead to problems. Suppose a component is programmed as a state machine and in a certain state only wants input from a particular port `P1`. What if input instead now appears on a different port `P2`. Since the `P2` message goes on the single input queue, the component now has to deal with it, by either dropping it, or put it back. This problem cannot be solved with priorities since such are of static nature and cannot be dependent on the state the component is in at a particular point in time.

### Solution: filtered read

The same problem appears in the standard actor model. Here it is suggested to handle this with a filtered read of the single input queue: read a message satisfying a certain predicate, and leave all other messages in the queue.

### Solution: multiple input queues

The problem can be solved by having an input queue associated with each input port. The main thread would then have to select what queue to read from. This corresponds to the channel-based CSP/CCS model of concurrency, as found e.g. in Rust and Ocaml. MSL is also based on the concept of multiple input queues.

### Discussion

Multiple input queues can lead to starvation, but so can filtered reads.

## Broadcasting could be useful

Broadcasting seems to be a useful concept not currently supported by F'. That is: connecting a single output port to more than one input port. This conept can for example be useful when connecting in listener components monitoring the traffic on connections. Here one wants the original input port connected to an output port, but also the input port of a listener. However, the general concept of broadcasting seems useful and simple to implement.

### Solution: add broadcasting

## Active component subclasses passive component

Conceptually it may not be good terminology to have the class of active components subclass the class of pasive components. Tak for example the following statement, which sounds right but which is factually wrong: ``A queued input port can only be part of a passive component''. Since all components, also active, are passive components due to this subclassing, the above statement is vacuous, and does not present any information.

### Solution: don't let active component subclass passive component

## Is there a need for QueuedComponent?

I am not sure I see the need for the conecpt of a QueuedComponent. Why make this part of the framework?




