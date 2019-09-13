# Problems located in Imaging HSM

## Shutdown and Ready messages that are stored (missed) keep being recycled

Missed events are handled as follows. When the imaging HSM reaches the off state, it looks for the next event in the missed queue. If such a one exists it takes it out and re-submits it. The event, however, may not match what is expected in the off state, which is only TakeImage events. The result is that the miseed-queue grows and grows.

Fortunately, in this case, from a functional correctness point of view, the program works since the ShutDown and Ready events probably should be ignored in the off state anyway.

The more general problem, however, is that there could be other events missed in some other application, and more importantly: the queue of missed events keeps growing.


