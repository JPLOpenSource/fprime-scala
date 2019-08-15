
/**
 * Internal DSL for F', supporting components, ports, commanding, parameters, and telelemtry.
 * F' is a framework (library, internal DSL) for creating a system of parallel executing
 * components. Components can be active or passive. An active component contains an internal
 * thread, while a passive component is like a traditional class. A component's interface is
 * defined as a set of input ports and a set of output ports. A configuration of components
 * is defined by linking output ports to input ports.
 *
 * Ports can be synchronous or asynchronous. "Sending a message" over a synchronous port
 * corresponds to calling a method, with an immediate return of a result value. In contrast,
 * sending a message over an asynchronous port corresponds to message passing as in the actor
 * model: the message ends up in the receiving components input queue for later processing by
 * the component's thread. This is a non-blocking operation seen from the sender's point of view.
 * A passive components cannot have asynchronous input ports.
 *
 * The F' framework also supports sending comands to components, receiveing telemetry from
 * components, and setting parameters in components.
 *
 * The thread inside an active component repeatedly reads its input queue and processes the next
 * input value sent to one of its input ports. All input ports are connected to the same single
 * input queue.
 */

package object fprime {}
