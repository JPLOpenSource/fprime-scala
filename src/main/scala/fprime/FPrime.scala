package fprime

import scala.language.postfixOps
import akka.actor.{Actor, ActorRef, ActorSystem, Props, ReceiveTimeout}
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import java.io._

/**
 * F' options to be set by the user.
 */

object FPrimeOptions {

  /**
   * Debug flag. When set to true various debugging information will
   * be printed on standard out as an application executes. The default
   * value is false.
   */

  var DEBUG : Boolean = false
}

/**
 * Utilities.
 */

object Util {
  /**
   * Names of telemetry data sent to ground.
   */
  type TelemetryName = String

  /**
   * Names of parameters that can be set in a component.
   */
  type ParameterName = String

  /**
   * Prints a message on standard out if the `DEBUG` flag is set.
   * Used for debugging purposes.
   *
   * @param msg the message to be printed.
   */

  def debug(msg : => String): Unit = {
    if (FPrimeOptions.DEBUG) println(s"[fpr] $msg")
  }

  /**
   * The following Akka configuration instructs Akka to serialize all
   * messages going over ports. This is used in F' to make messaging
   * medium independent. It is not needed in a single memory model.
   */

  val conf =
    ConfigFactory.parseString(
      """
        |akka {
        |  actor {
        |    serialize-messages = on
        |  }
        |}
        |""".stripMargin
    )
  // println(s"""serialization is ${conf.getString("akka.actor.serialize-messages")}""")
  // val system: ActorSystem = ActorSystem("F-Prime", conf)
  val system: ActorSystem = ActorSystem("F-Prime")
}

import Util._

/**
 * This class supports the visualization of components and their
 * connections using the GraphViz dot format.
 */

object Configuration {
  /**
   * Contains connections created between components. Each connection is represented
   * by a triple of the form: `(source, target, kind)`, all string values,
   * where `source` is the name of the component possessing the output port
   * and `target` is the name of the component possessing the input port,
   * and where `kind` is the kind of input port: `"Input"` (normal input port),
   * `"ObsInput"` (an observation port), `"CommandInput"` (a command port).
   */

  private var connections : List[(String,String,String)] = Nil

  /**
   * Adds a connection. A connection is represented by a triple of the form:
   * `(source, target, kind)`, all string values, where `source` is the name of the
   * component possessing the output port and `target` is the name of the component
   * possessing the input port, and where `kind` is the kind of input port: `"Input"`
   * (normal input port), `"ObsInput"` (an observation port), `"CommandInput"`
   * (a command port).
   *
   * @param source the component sending messages.
   * @param target the component receiving messages.
   * @param kind the kind of input port at the receiving end:
   *             `"Input"`, `"ObsInput"`, or `"CommandInput"`.
   */

  private[fprime] def addConnection(source: String, target: String, kind: String): Unit = {
    connections = (source, target, kind) :: connections
  }

  /**
   * Prints the component topology in GraphViz's dot format.
   * An example is the following.
   *
   * {{{
   *   digraph Components  {
   *     Imaging [shape=box]
   *     Camera  [shape=box]
   *     Ground  [shape=oval]
   *
   *     Imaging -> Camera
   *     Camera -> Imaging
   *     Camera -> Ground [style=dashed, color="#ff0000"]
   *     Imaging -> Ground [style=dashed, color="#ff0000"]
   *     Ground -> Imaging [color="#0000ff"]
   *   }
   * }}}
   *
   * @param fileName name of file to write dot file to.
   */

  def show(fileName: String): Unit = {
    val pw = new PrintWriter(new File(fileName))
    val components : Set[String] =
      (for ((source,target,_) <- connections) yield Set(source,target)).flatten.toSet
    pw.write("digraph Components  {\n")
    for (component <- components) {
      pw.write(s"  $component")
      val shape = if (component == "Ground") "oval" else "box"
      pw.write(s" [shape=$shape]\n")
    }
    pw.write("\n")
    for ((source, target, kind) <- connections) {
      pw.write(s"  $source -> $target")
      val format = kind match {
        case "Input" => ""
        case "ObsInput" => "[style=dashed, color=\"#ff0000\"]"
        case "CommandInput" => "[color=\"#0000ff\"]"
      }
      pw.write(s" $format\n")
    }
    pw.write("}\n")
    pw.close()
  }
}

// =========================
// === Synchronous Ports ===
// =========================

/**
 * A synchronous input port corresponds to a method in a class. When invoked with an argument,
 * it will return a value.
 *
 * @tparam S type of argument value.
 * @tparam T type of return value.
 */

trait SyncInput[S, T] {

  /**
   * Calling the invoke method corresponds to the method call. The method has to be defined
   * in the component by the user to performed the desired action.
   *
   * @param a parameter to the method call.
   * @return return value from the method call, can be of type Unit.
   */

  def invoke(a: S): T
}

/**
 * A guarded synchronous port corresponds to a synchronized method in Java: when invoked,
 * a lock is taken on the component's mutex, which is released upon return. This is the approach
 * to shared lock protected memory.
 *
 * @param mutex the mutex upon which a lock is taken. It is defined as implicit, assuming
 *              it will be in scope (as implicit) in the component where the port is created.
 * @tparam S type of argument value.
 * @tparam T type of return value.
 */

class GuardedSyncInput[S, T](implicit mutex: Mutex) extends SyncInput[S, T] {
  /**
   * The `invoke` method calls `invokeGuarded` under a lock
   * on the mutex.
   *
   * @param a parameter to the method call.
   * @return return value from the method call, can be of type Unit.
   */

  def invoke(a: S): T = {
    mutex.synchronized {
      invokeGuarded(a)
    }
  }

  /**
   * Called by `invoke` under a lock on the mutex. The user has to override
   * this method to perform the desired action.
   *
   * @param a parameter to the method call.
   * @return return value from the method call, can be of type Unit.
   */

  protected def invokeGuarded(a: S): T = {
    assert(false, "invokeGuarded has not been defined!").asInstanceOf[Nothing]
  }
}

/**
 * A synchronous output port corresponds to a method call. When invoked with an argument,
 * it will return a value. A synchronous output port must be connected to a synchronous
 * input port.
 *
 * @tparam S type of argument value.
 * @tparam T type of return value.
 */

class SyncOutput[S, T] {
  /**
   * A synchronous output port is connected to exactly one synchronous input port.
   */

  private var otherEnd: SyncInput[S, T] = null

  /**
   * Connects this synchronous output port to a synchronous input port.
   *
   * @param in the synchronous input port to connect to.
   */

  def connect(in: SyncInput[S, T]): Unit = {
    otherEnd = in
  }

  /**
   * Calling the invoke method corresponds to the method call. The method calls
   * the `invoke` method in the connected input port.
   *
   * @param a parameter to the method call.
   * @return return value from the method call, can be of type Unit.
   */

  def invoke(a: S): T = {
    otherEnd.invoke(a)
  }
}

// ==========================
// === Asynchronous Ports ===
// ==========================

/**
 * An asynchronous input port interface.
 *
 * @tparam T type of message passed to port.
 */

trait iInput[T] {
  /**
   * Calling the invoke method corresponds to sending a message to the component containing
   * the input port as part of its interface. The message will go on the component's
   * single message queue and later processed by the component's thread.
   *
   * @param a the message being sent
   */

  def invoke(a: T)

  /**
   * Returns the name of the component of which this input port is part. Used
   * for printing out debugging information.
   *
   * @return name of component of which this input port is part.
   */

  def getComponentName: String
}

/**
 * Asynchronous input port.
 *
 * @param actorRef      reference to Akka actor thread running internally in active component.
 *                      Any input is sent to that actor thread. Defined as implicit and assumed to
 *                      be in scope in the active component the port is part of.
 * @param thisComponent the component this port is part of, seen as a passive component. Note that
 *                      an active component subclasses passive component. Defined as implicit and assumed to
 *                      be in scope in the active component the port is part of.
 * @tparam T type of message passed to port.
 */

class Input[T](implicit actorRef: ActorRef, implicit val thisComponent: PassiveComponent) extends iInput[T] {

  /**
   * Calling the invoke method corresponds to sending a message to the component containing
   * the input port as part of its interface. The message will be sent to the Akka actor
   * representing the component's thread.
   *
   * The user can override this method in case the message should be transformed before being sent
   * to the actor, for example if an internal data structure is used to distinguish between the inputs
   * to different input ports. In that case the overridden user defined method can call
   * `invokeAny`, which is relaxed about the message type.
   *
   * @param a the message being sent
   */

  def invoke(a: T): Unit = {
    actorRef ! a
  }

  /**
   * Can be called if the method `invoke` is overridden by the user. Useful due to
   * the `Any` which does not care about the type of messages sent to the actor.
   *
   * @param a message being sent to actor.
   */

  protected def invokeAny(a: Any): Unit = {
    actorRef ! a
  }

  /**
   * Returns the name of the component of which this input port is part. Used
   * for printing out debugging information.
   *
   * @return name of component of which this input port is part.
   */

  def getComponentName: String = thisComponent.componentName
}

/**
 * A queued input port can only be part of a `QueuedComponent`. Messages sent to a
 * queued input port get stored in a queue. However, the queue is here not accessed by an
 * internal actor thread (there is no such). Instead it can be read by other components.
 *
 * @param queue     the queue into which messages are stored. Defined as implicit and assumed to
 *                  be in scope in the queued component the port is part of.
 * @param mutex     mutex the mutex upon which a lock is taken. Defined as implicit and assumed to
 *                  be in scope in the queued component the port is part of.
 * @param component the queued component this port is part of, seen as a passive component.
 *                  Defined as implicit and assumed to be in scope in the queued component
 *                  the port is part of.
 * @tparam T type of message passed to port.
 */

class QueuedInput[T](
                      implicit val queue: scala.collection.mutable.Queue[T],
                      implicit val mutex: Mutex,
                      implicit val component: PassiveComponent) extends iInput[T] {
  /**
   * Calling the invoke method corresponds to sending a message to the component containing
   * the input port as part of its interface. The message will be stored in the queue, to
   * be accessed by other components. The method is thread safe (a mutex protects the queue
   * operation).
   *
   * @param a the message being sent
   */

  def invoke(a: T): Unit = {
    mutex.synchronized {
      queue += a
    }
  }

  /**
   * Returns the name of the component of which this input port is part. Used
   * for printing out debugging information.
   *
   * @return name of component of which this input port is part.
   */

  def getComponentName: String = component.componentName
}

/**
 * Asynchronous output port.
 *
 * @param componentName name of the component the port is part of. Defined as implicit
 *                      and assumed to be in scope in the active component the port is part of.
 * @tparam T type of messages sent on port.
 */

class Output[T](implicit componentName: String) {
  /**
   * The list of input ports this output port is connected to. The fact that
   * it is a list allows for broadcasting.
   */

  private var otherEnds: List[iInput[T]] = List()

  /**
   * Connects this asynchronous output port to one or more asynchronous input ports.
   *
   * @param in the asynchronous input ports to connect to, provided as varargs.
   */

  def connect(in: iInput[T]*): Unit = {
    for (target <- in) {
      Configuration.addConnection(componentName, target.getComponentName, target.getClass.getSimpleName)
    }
    otherEnds ++= in.toList
  }

  /**
   * Calling the invoke method causes the argument message to be sent to the input
   * ports registered as receivers.
   *
   * @param a the message being sent.
   */

  def invoke(a: T): Unit = {
    assert(!otherEnds.isEmpty, "Output port has not been connected to input port when sending $a")
    for (otherEnd <- otherEnds) {
      debug(s"! $componentName -[$a]-> ${otherEnd.getComponentName}")
      otherEnd.invoke(a)
    }
  }
}

/**
 * An array output port supports an array of individual asynchronous output ports.
 * Each individual output port can be invoked using its port index.
 *
 * @param size the size of the port array.
 * @tparam T the type of of message sent on the individual output ports. They must
 *           all be of the same type.
 */

class ArrayOutput[T](size: Int) {
  private var portArray: Array[iInput[T]] = new Array(size)
  private var listeners: List[iInput[T]] = List()

  /**
   * Connects this array output port to its indexed input ports, one for each index.
   *
   * @param bindings the (index, asynchronous input port) pairs to connect to, provided as varargs.
   */

  def connect(bindings: (Int, iInput[T])*): Unit = {
    for ((index, inPort) <- bindings) {
      portArray(index) = inPort
    }
  }

  /**
   * It is with this method possible to connect to additional input ports besides those
   * indexed in the array. This can be used to e.g. add listeners to the communication
   * sent via the indexed ports.
   *
   * @param in the additional asynchronous input ports to connect to, provided as varargs.
   */

  def connectListeners(in: iInput[T]*): Unit = {
    listeners ++= in.toList
  }

  /**
   * Calling the invoke method causes the argument message to be sent to the input
   * port at the given index. Each such message also gets broadcasted to ports
   * registered with the `connectListeners` method.
   *
   * @param index the index of the input port to send to.
   * @param a     the message being sent.
   */

  def invoke(index: Int, a: T): Unit = {
    assert(portArray.indices forall (portArray(_) != null), "Output array port has not been fully connected")
    portArray(index).invoke(a)
    for (otherEnd <- listeners) {
      otherEnd.invoke(a)
    }
  }
}

// ===========================================
// === Command, Event, and Telemetry Ports ===
// ===========================================

// === Data types for communication with ground:
// === commands, events, and telemetry

// Data sent from ground to FSW

/**
 * Commands from ground controls the spacecraft/rover. Commands can be
 * defined as case classes and case objects subclassing this trait.
 */

trait Command extends Serializable { // subclass this

  /**
   * True if the command is urgent. An urgent command sent to an asynchronous
   * input port gets processed right away: it does not enter the input queue
   * first.
   */

  private[fprime] var isUrgent: Boolean = false

  /**
   * This method can be called in a chaining manner. Suppose that `cmd` is
   * a command. Then `cmd.urgent` is the same command as `cmd`
   * except with the `isUrgent` flag set to true.
   *
   * @return the command updated with the `isUrgent` flag set to true.
   */

  def urgent: Command = {
    isUrgent = true
    this
  }
}

/**
 * A special command is the `Parameters` case class, which instructs setting
 * indicated parameter names to denote indicated integer values.
 *
 * @param data the mapping of parameter names to the parameter values they shall denote.
 */

case class Parameters(data: Map[ParameterName, Int]) extends Command

// Data sent from FSW to ground

/**
 * Observations are data sent from the spacecraft/rover to ground. Any observation type must
 * subclass this trait.
 */

trait Observation extends Serializable // subclass this

/**
 * Telemetry is a special kind of observation, which provides information about the value
 * of a collection of variables having floating point values. This can e.g. be speed,
 * orientation, temperature, etc.
 *
 * @param data The telemetry observations in the form of a mapping from telemetry names to values.
 */

case class Telemetry(data: Map[TelemetryName, Float]) extends Observation

/**
 * Events are observations sent to ground indicating discrete events occurring, such as
 * e.g. a command being dispatched, and a command succeeding. Events can be defined as
 * case classes and case objects subclassing this trait.
 */

trait Event extends Observation // subclass this

// === The commanding and observation ports

// FSW input

/**
 * The `CommandInput` is a special `Input` port which accepts commands from
 * ground. The port is special by handling a command right away in case the command has been
 * declared as urgent (a command `cmd` is declared urgent by calling the `urgent`
 * method on it as follows: `cmd.urgent`).
 *
 * @param actorRef  reference to Akka actor thread running internally in active component.
 *                  Any input is sent to that actor thread. Defined as implicit and assumed to
 *                  be in scope in the active component the port is part of.
 * @param component the component this port is part of, seen as a passive component. Note that
 *                  an active component subclasses passive component. Defined as implicit and assumed to
 *                  be in scope in the active component the port is part of.
 */

class CommandInput(implicit actorRef: ActorRef, implicit val component: PassiveComponent) extends Input[Command] {
  /**
   * Calling the invoke method corresponds to sending a command to the component containing
   * the input port as part of its interface. The command will be sent to the Akka actor
   * representing the component's thread, unless the command is urgent, in which case it will
   * be processed right away (it is not going into the component's actor queue). All commands
   * will be processed by the user defined `processCommand`, which the user has to
   * override/define in the component containing the port.
   *
   * @param cmd the command being sent.
   */

  override def invoke(cmd: Command): Unit = {
    if (cmd.isUrgent) {
      thisComponent.processCommand(cmd)
    } else {
      actorRef ! cmd
    }
  }
}

// FSW output

/**
 * An `ObsOutput` port is a special `Output` port to which observations
 * can be sent, including events and telemetry. Events can be submitted with the standard
 * `invoke` method. Telemetry can be submitted with the special `logTelem`
 * method.
 *
 * @param componentName name of the component the port is part of. Defined as implicit
 *                      and assumed to be in scope in the active component the port is part of.
 */

class ObsOutput(implicit componentName: String) extends Output[Observation] {
  /**
   * Submits a telemetry observation reporting on the values of telemetry variables.
   *
   * @param data          the mapping from telemetry names to values to be reported.
   * @param componentName name of the component the port is part of. Defined as implicit
   *                      and assumed to be in scope in the active component the port is part of.
   */

  def logTelem(data: (TelemetryName, Float)*)(implicit componentName: String): Unit = {
    val map = data.map {
      case (x, y) => (componentName + "." + x, y)
    }
    invoke(Telemetry(map.toMap))
  }

  /**
   * Submits an event. A call `logEvent(e)` has the same meaning as `invoke(e)`,
   * and is introduced only to make calls of event logging stand out in the
   * program text.
   *
   * @param event the event being submitted as an observation.
   */

  def logEvent(event: Event): Unit = {
    invoke(event)
  }
}

// Ground input

/**
 * Special input port for ground for receiving observations from the spacecraft/rover.
 *
 * @param actorRef  reference to Akka actor thread running internally in active ground component.
 *                  Any input is sent to that actor thread. Defined as implicit and assumed to
 *                  be in scope in the active component the port is part of.
 * @param component the ground component this port is part of, seen as a passive component. Note that
 *                  an active component subclasses passive component. Defined as implicit and assumed to
 *                  be in scope in the active component the port is part of.
 */

class ObsInput(implicit actorRef: ActorRef, implicit val component: PassiveComponent) extends Input[Observation]

// Ground output

/**
 * Special output port for ground for sending commmands.
 *
 * @param componentName name of the ground component the port is part of. Defined as implicit
 *                      and assumed to be in scope in the active component the port is part of.
 */

class CommandOutput(implicit componentName: String) extends Output[Command]

// ==================
// === Components ===
// ==================

/**
 * Mutex used to achieve thread safe mutually exclusive access to
 * variables of passive components.
 */

private[fprime] class Mutex

/**
 * A passive component is like a class. Sending a message to a passive component is like calling
 * a method on the component, which returns with a value (potentially of type `Unit`
 * if the "method" is just called for its side effect).
 */

trait PassiveComponent {
  /**
   * The mutex protecting the data of this component from parallel access from
   * different components.
   */

  protected[fprime] implicit val mutex = new Mutex

  /**
   * The name of this component.
   */

  protected[fprime] implicit val componentName = this.getClass.getSimpleName

  /**
   * A reference to this component.
   */

  protected[fprime] implicit val thisComponent: PassiveComponent = this

  /**
   * The parameters of this component, which can be assigned values with the
   * `Parameters` command from ground. The parameters can be
   * accessed from within the component to influence its behavior.
   */

  protected implicit var parameters: Map[ParameterName, Int] = Map()

  /**
   * Sets the parameters of component based on a `Parameters` command from ground.
   *
   * @param parameters the `Parmeters` command.
   */

  protected def setParameters(parameters: Parameters): Unit = {
    val Parameters(map) = parameters
    for ((name, value) <- map) {
      setParameter(name, value)
    }
  }

  /**
   * Set a single parameter.
   *
   * @param name  name of the parameter.
   * @param value value the parameter is assigned.
   */

  private def setParameter(name: ParameterName, value: Int): Unit = {
    parameters += (name -> value)
  }

  /**
   * Get the value of a parameter.
   *
   * @param name name of the parameter.
   * @return value of the parameter.
   */

  protected def getParameter(name: ParameterName): Option[Int] = {
    parameters.get(name)
  }

  /**
   * This method is called to execute a command when an active component receives a such
   * through a `CommandInput` port. It is either called when the command
   * asynchronously is picked off the component's actor queue, or called directly in case the
   * command is urgent. The method calls the `processCommand` method which the user
   * has to define.
   *
   * @param cmd command to be executed.
   */

  private[fprime] def processCommand(cmd: Command): Unit = {
    cmd match {
      case parameters: Parameters => setParameters(parameters)
      case _ => executeCommand(cmd)

    }
  }

  /**
   * Executes a command. The method has to be overridden and defined by the user as part of
   * the component definition. The method will assertion fail by default for any command.
   *
   * @param cmd command to be executed.
   */

  protected def executeCommand(cmd: Command): Unit = {
    assert(false, s"*** no command handling implemented for component $componentName. $cmd is not processed!")
  }
}

/**
 * A queued component is a passive component (like a class), which has an additional
 * queue of messages built in. A queued component can have normal synchronized ports,
 * as can passive components, but it can also have asynchronous queued input ports, which
 * when invoked asynchronously , will place the incoming message in the queue.
 *
 * @tparam T type of messages inserted in queue.
 */

trait QueuedComponent[T] extends PassiveComponent {
  /**
   * The queue of messages.
   */

  implicit val queue = scala.collection.mutable.Queue[T]()

  /**
   * Inserting a message in the queue. Thread safe.
   *
   * @param x message being inserted into queue.
   */

  def put(x: T): Unit = {
    mutex.synchronized {
      queue += x
    }
  }

  /**
   * Getting the next element of the queue (FIFO). Thread safe.
   *
   * @return the next element `v` in the queue as `Some(v)`
   *         if the queue is not empty, and `None` if the queue is
   *         empty.
   */

  def get(): Option[T] = {
    mutex.synchronized {
      if (queue.isEmpty) None else Some(queue.dequeue())
    }
  }
}

/**
 * An active component behaves like a thread to which messages can be sent over ports
 * asynchronously. However, an active component can also have syncrhonous ports (like
 * methods to be called).
 */

trait Component extends PassiveComponent {
  /**
   * It meant to define the behavior of the component for each new message
   * submitted to the component. It must be overridden and defined by the user.
   * Incoming messages are of type `Any` and the method returns no
   * value of importance.
   *
   * @return no return value beyond `()` of type `Unit`.
   */

  protected def when: PartialFunction[Any, Unit]

  /**
   * A reference to the actor serving this active component. Each active component
   * runs an actor.
   */

  protected implicit var actorRef: ActorRef = system.actorOf(Props(new TheActor))

  /**
   * Variable indicating whether a timer has been set with the `setTimer`
   * method.
   */

  private var timeOut: Option[Int] = None

  /**
   * Sets the timer to a specific number of seconds.
   *
   * @param time time in milliseconds.
   */

  protected def setTimer(time: Int): Unit = {
    timeOut = Some(time)
  }

  /**
   * Used for a component to send itself a message. This can be used to
   * allow the component to enter a state where no external event is expected,
   * and where the component sends itself a message to progress.
   *
   * @param msg the message the component sends itself.
   */

  protected def selfTrigger(msg: Any): Unit = {
    actorRef ! msg
  }

  /**
   * The actor that represents the individual behavior of this active component.
   * Scala's Akka actors are used for this purpose.
   */

  private class TheActor extends Actor {

    /**
     * This method is called after each event processed by the actor. It initiates an actor timer if the
     * `timeOut` variable has been set with the `setTimer`
     * method. The actor timer will time out after the period indicated in the `timeOut` variable,
     * by sending a `ReceiveTimeout` message to the actor, unless it is neutralized beforehand,
     * e.g. when an expected different message is received.
     */

    private def resetTimer(): Unit = {
      timeOut match {
        case None =>
        case Some(time) =>
          debug(s">>> set timer with $time milliseconds in $componentName")
          context.setReceiveTimeout(time milliseconds)
          timeOut = None
      }
    }

    /**
     * This method processes messages sent to the actor. If it is a parameter
     * command, it sets the parameters accordingly.
     * If it is a timeout, it calls the user defined `when` on it if
     * the `when` method is ready to receive a timeout. If not the timeout
     * is dropped permanently. If none of these cases apply, it is tested whether
     * `when` is defined for the event. If it is it calls the user defined `when` method on it.
     * This method should not be overridden by the user. If none of these cases
     * apply, the event is dropped, and handled by the `unhandled` method,
     * which re-inserts the message in the message queue for later consumption.
     *
     * @return the method does not return any value of interest.
     */

    override def receive: PartialFunction[Any, Unit] = {
      case parameters: Parameters =>
        setParameters(parameters)
      case ReceiveTimeout =>
        debug(s"<<< timeout in $componentName")
        context.setReceiveTimeout(Duration.Undefined)
        if (when.isDefinedAt(ReceiveTimeout)) when(ReceiveTimeout)
        resetTimer()
      case other =>
        debug(s"? $componentName : $other")
        when(other)
        resetTimer()
    }

    /**
     * Akka actors by default drop messages in the input queue that do not
     * match the partial function provided to the `receive` method. This is
     * undesirable from an F' point of view. When a message is dropped this
     * method is called by Akka on the dropped messsage. Here the method is
     * overridden to put the message back on the queue.
     *
     * A preferable solution would be that non-matching messages are not
     * removed from the queue. It is, however, not clear whether this can
     * be achieved.
     *
     * @param message the dropped message.
     */

    override def unhandled(message: Any): Unit = {
      assert(false, s"+++++ $componentName LOST MESSAGE: $message")
    }
  }

}

