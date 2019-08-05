package fprime

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

import scala.language.postfixOps
import akka.actor.{Actor, ActorRef, ActorSystem, Props, ReceiveTimeout}
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._

/** ****
 * Util
 * *****/

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
   * The <code>invoke</code> method calls <code>invokeGuarded</code> under a lock
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
   * Called by <code>invoke</code> under a lock on the mutex. The user has to override
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
   * A synchronous port is connected to exactly one synchronous input port.
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
   * the <code>invoke</code> method in the connected input port.
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
   * <code>invokeAny</code>, which is relaxed about the message type.
   *
   * @param a the message being sent
   */

  def invoke(a: T): Unit = {
    actorRef ! a
  }

  /**
   * Can be called if the method <code>invoke</code> is overridden by the user. Useful due to
   * the <code>Any</code> which does not care about the type of messages sent to the actor.
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
 * A queued input port can only be part of a passive component.
 *
 * @param queue
 * @param mutex
 * @param component
 * @tparam T type of message passed to port.
 */

class QueuedInput[T](
                      implicit val queue: scala.collection.mutable.Queue[T],
                      implicit val mutex: Mutex,
                      implicit val component: PassiveComponent) extends iInput[T] {
  /**
   * Calling the invoke method corresponds to sending a message to the component containing
   * the input port as part of its interface. The message will be sent to the Akka actor
   * representing the component's thread.
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

class Output[T](implicit componentName: String) {
  var otherEnds: List[iInput[T]] = List()

  def connect(in: iInput[T]*): Unit = {
    otherEnds ++= in.toList
  }

  def connectListeners(in: iInput[T]*): Unit = {
    connect(in: _*)
  }

  def invoke(a: T): Unit = {
    assert(!otherEnds.isEmpty, "Output port has not been connected to input port when sending $a")
    for (otherEnd <- otherEnds) {
      otherEnd.invoke(a)
      debug(componentName, otherEnd.getComponentName, a)
    }
  }

  def debug(source: String, target: String, msg: T): Unit = {
    val otherComponent =
      println(s"$source ---> $target : [$msg]")
  }
}

class ArrayOutput[T](size: Int) {
  var portArray: Array[iInput[T]] = new Array(size)
  var listeners: List[iInput[T]] = List()

  def allArrayEntriesDefined(): Boolean = {
    portArray.indices forall (portArray(_) != null)
  }

  def connect(bindings: (Int, iInput[T])*): Unit = {
    for ((index, inPort) <- bindings) {
      portArray(index) = inPort
    }
  }

  def connectListener(in: iInput[T]*): Unit = {
    listeners ++= in.toList
  }

  def invoke(index: Int, a: T): Unit = {
    assert(allArrayEntriesDefined(), "Output array port has not been fully connected")
    portArray(index).invoke(a)
    for (otherEnd <- listeners) {
      otherEnd.invoke(a)
    }
  }
}

/** ***********************************
 * Commands, Event and Telemetry Ports
 * ************************************/

// === The data ===

// To FSW

trait Command extends Serializable { // subclass this
  var isUrgent: Boolean = false

  def urgent: Command = {
    isUrgent = true
    this
  }
}

case class Parameters(data: Map[ParameterName, Int]) extends Command

// From FSW

trait Observation extends Serializable // subclass this

case class Telemetry(data: Map[TelemetryName, Float]) extends Observation

trait Event extends Observation // subclass this

// === The ports ===

// -----------
// --- FSW ---
// -----------

// Input

class CommandInput(implicit actorRef: ActorRef, implicit val component: PassiveComponent) extends Input[Command] {
  override def invoke(cmd: Command): Unit = {
    if (cmd.isUrgent) {
      thisComponent.processTheCommand(cmd)
    } else {
      actorRef ! cmd
    }
  }
}

// Output

class ObsOutput(implicit componentName: String) extends Output[Observation] {
  def log(obs: Observation): Unit = {
    invoke(obs)
  }

  def logTelem(data: (TelemetryName, Float)*)(implicit componentName: String): Unit = {
    val map = data.map {
      case (x, y) => (componentName + "." + x, y)
    }
    invoke(Telemetry(map.toMap))
  }
}

// --------------
// --- Ground ---
// --------------

// Input

class ObsInput(implicit actorRef: ActorRef, implicit val component: PassiveComponent) extends Input[Observation]

// Output

class CommandOutput(implicit componentName: String) extends Output[Command]

/** ****************
 * PassiveComponent
 * *****************/

class Mutex

trait PassiveComponent {
  implicit val mutex = new Mutex
  implicit val componentName = this.getClass.getSimpleName
  implicit var parameters: Map[ParameterName, Int] = Map()
  implicit val thisComponent: PassiveComponent = this

  def setParameters(parameters: Parameters): Unit = {
    val Parameters(map) = parameters
    for ((name, value) <- map) {
      setParameter(name, value)
    }
  }

  def setParameter(name: ParameterName, value: Int): Unit = {
    parameters += (name -> value)
  }

  def getParameter(name: ParameterName): Option[Int] = {
    parameters.get(name)
  }

  def processTheCommand(cmd: Command): Unit = {
    cmd match {
      case parameters: Parameters => setParameters(parameters)
      case _ => processCommand(cmd)

    }
  }

  def processCommand(cmd: Command): Unit = {
    println(s"*** no command handling implemented for component $componentName. $cmd is not processed!")
  }
}

/** ***************
 * ActiveComponent
 * ****************/

trait Component extends PassiveComponent {
  def when: Actor.Receive

  implicit var actorRef: ActorRef = system.actorOf(Props(new TheActor))

  var timeOut: Option[Int] = None

  def setTimer(time: Int): Unit = {
    timeOut = Some(time * 1000)
  }

  def selfTrigger(msg: Any): Unit = {
    actorRef ! msg
  }

  class TheActor extends Actor {
    def resetTimer(): Unit = {
      timeOut match {
        case None =>
        case Some(time) =>
          // println(s"activating timer with $time milliseconds in $componentName")
          context.setReceiveTimeout(time milliseconds)
          timeOut = None
      }
    }

    override def receive: Receive = {
      case cmd: Command =>
        processCommand(cmd)
        resetTimer()
      case ReceiveTimeout =>
        context.setReceiveTimeout(Duration.Undefined)
        when(ReceiveTimeout)
        resetTimer()
      case other =>
        when(other)
        resetTimer()
    }
  }

}

/** ***************
 * QueuedComponent
 * ****************/

trait QueuedComponent[T] extends PassiveComponent {
  implicit val queue = scala.collection.mutable.Queue[T]()

  def put(x: T): Unit = {
    mutex.synchronized {
      queue += x
    }
  }

  def get(): Option[T] = {
    mutex.synchronized {
      if (queue.isEmpty) None else Some(queue.dequeue())
    }
  }
}
