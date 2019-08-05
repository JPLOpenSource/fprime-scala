package fprime

/**
 * Internal DSL for F', supporting components, ports, commanding, parameters, and telelemtry.
 */

import scala.language.postfixOps
import akka.actor.{Actor, ActorRef, ActorSystem, Props, ReceiveTimeout}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

/** ****
 * Util
 * *****/

object Util {
  type TelemetryName = String
  type ParameterName = String

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

/** ***********
 * Normal Ports
 * ************/

// === Synchronous ===

trait SyncInput[S, T] {
  def invoke(a: S): T
}

class GuardedSyncInput[S, T](implicit mutex: Mutex) extends SyncInput[S, T] {
  def invoke(a: S): T = {
    mutex.synchronized {
      invokeGuarded(a)
    }
  }

  def invokeGuarded(a: S): T = {
    assert(false, "invokeGuarded has not been defined!").asInstanceOf[Nothing]
  }
}

class SyncOutput[S, T] {
  var otherEnd: SyncInput[S, T] = null

  def connect(in: SyncInput[S, T]): Unit = {
    otherEnd = in
  }

  def invoke(a: S): T = {
    otherEnd.invoke(a)
  }
}

// === Asynchronous ===

trait iInput[T] {
  def invoke(a: T)

  def getComponentName: String
}

class Input[T](implicit actorRef: ActorRef, implicit val thisComponent : PassiveComponent) extends iInput[T] {
  def invoke(a: T): Unit = {
    actorRef ! a
  }

  def invokeAny(a: Any): Unit = {
    actorRef ! a
  }

  def getComponentName: String = thisComponent.componentName
}

class QueuedInput[T](implicit val queue: scala.collection.mutable.Queue[T], implicit val mutex: Mutex, implicit val component : PassiveComponent) extends iInput[T] {
  def invoke(a: T): Unit = {
    mutex.synchronized {
      queue += a
    }
  }

  def getComponentName: String = component.componentName
}

class Output[T](implicit componentName: String) {
  var otherEnds: List[iInput[T]] = List()

  def connect(in: iInput[T]*): Unit = {
    otherEnds ++= in.toList
  }

  def connectListeners(in: iInput[T]*): Unit = {
    connect(in : _*)
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

  implicit val thisComponent: PassiveComponent = this

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

  var timeOut : Option[Int] = None

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
