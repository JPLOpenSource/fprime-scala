package fprime13_camera

/**
 * The imaging system described in the paper:
 * "Modeling and Monitoring of Hierarchical State Machines in Scala",
 * Klaus Havelund and Rajeev Joshi,
 * 9th International Workshop on Software Engineering for Resilient Systems (SERENE 2017),
 * September 4-5, 2017, Geneva, Switzerland. Lecture Notes in Computer Science Volume 10479.
 */

import fprime._
import hsm._
import daut._

import akka.actor.ReceiveTimeout
import scala.language.postfixOps

// Messages:

case class TakeImage(d: Int) extends Command

case object ShutDown extends Command

trait Imaging2Camera

case object Open extends Imaging2Camera

case object Close extends Imaging2Camera

case object PowerOn extends Imaging2Camera

case object PowerOff extends Imaging2Camera

case object SaveData extends Imaging2Camera

trait Camera2Imaging

case object Ready extends Camera2Imaging

case class EvrTakeImage(d: Int) extends Event

case object EvrOpen extends Event

case object EvrClose extends Event

case object EvrPowerOn extends Event

case object EvrPowerOff extends Event

case object EvrSaveData extends Event

case object EvrImageSaved extends Event

case object EvrImageAborted extends Event

// Components:

class Imaging extends Component {
  val i_cmd = new CommandInput
  val i_cam = new Input[Camera2Imaging]
  val o_cam = new Output[Imaging2Camera]
  val o_obs = new ObsOutput

  object MissedEvents {
    private var missedEvents : List[Any] = Nil

    def add(event : Any): Unit = {
      missedEvents ++=  List(event)
    }

    def submit(): Unit = {
      missedEvents match {
        case Nil =>
        case event :: rest =>
          missedEvents = rest
          selfTrigger(event)
      }
    }
  }

  object Machine extends HSM[Any] {
    var duration: Int = 0
    val DARK_THRESHOLD = 5

    def getTemp(): Int = 15

    states(off, on, powering, exposing, exposing_light, exposing_dark, saving)
    initial(off)

    object off extends state() {
      entry {
        MissedEvents.submit()
      }
      when {
        case TakeImage(d) => on exec {
          o_obs.logEvent(EvrTakeImage(d))
          duration = d
          o_cam.invoke(PowerOn)
        }
      }
    }

    object on extends state() {
      when {
        case ShutDown => off exec {
          o_obs.logEvent(EvrImageAborted)
          o_cam.invoke(PowerOff)
        }
      }
    }

    object powering extends state(on, true) {
      when {
        case Ready => exposing
      }
    }

    object exposing extends state(on)

    object exposing_light extends state(exposing, true) {
      entry {
        o_cam.invoke(Open)
        setTimer(duration)
      }
      exit {
        o_cam.invoke(Close)
      }
      when {
        case ReceiveTimeout => {
          if (getTemp() >= DARK_THRESHOLD) exposing_dark else saving
        }
      }
    }

    object exposing_dark extends state(exposing) {
      entry {
        setTimer(duration)
      }
      when {
        case ReceiveTimeout => saving
      }
    }

    object saving extends state(on) {
      entry {
        o_cam.invoke(SaveData)
      }
      when {
        case Ready => off exec {
          o_obs.logEvent(EvrImageSaved)
          o_cam.invoke(PowerOff)
        }
      }
    }

  }

  override def when: PartialFunction[Any, Unit] = {
    case input => if (!Machine(input)) MissedEvents.add(input)
  }
}

class Camera extends Component {
  val i_img = new Input[Imaging2Camera]
  val o_img = new Output[Camera2Imaging]
  val o_obs = new ObsOutput

  def open(): Unit = {
    o_obs.invoke(EvrOpen)
  }

  def close(): Unit = {
    o_obs.invoke(EvrClose)
  }

  def powerOn(): Unit = {
    o_obs.invoke(EvrPowerOn)
  }

  def powerOff(): Unit = {
    o_obs.invoke(EvrPowerOff)
  }

  def saveData(): Unit = {
    o_obs.invoke(EvrSaveData)
  }

  override def when: PartialFunction[Any, Unit] = {
    case Open => open()
    case Close => close()
    case PowerOn => powerOn(); o_img.invoke(Ready)
    case PowerOff => powerOff()
    case SaveData => saveData(); o_img.invoke(Ready)
  }
}

class Ground extends Component {
  val i_int = new Input[Int]
  val i_obs = new ObsInput
  val o_cmd = new CommandOutput

  object SaveOrAbort extends Monitor[Observation] {
    always {
      case EvrTakeImage(_) => hot {
        case EvrImageSaved | EvrImageAborted => ok
        case EvrTakeImage(_) => error("Image was not saved or aborted")
      }
    }
  }

  override def when: PartialFunction[Any, Unit] = {
    case d: Int => o_cmd.invoke(TakeImage(d))
    case o: Observation => SaveOrAbort.verify(o)
  }
}

// Main:

object Main {
  def main(args: Array[String]): Unit = {
    FPrimeOptions.DEBUG = true
    HSMOptions.DEBUG = true
    HSMOptions.TRACE = true

    val imaging = new Imaging
    val camera = new Camera
    val ground = new Ground

    imaging.o_cam.connect(camera.i_img)
    imaging.o_obs.connect(ground.i_obs)
    camera.o_img.connect(imaging.i_cam)
    camera.o_obs.connect(ground.i_obs)
    ground.o_cmd.connect(imaging.i_cmd)

    Configuration.show("/Users/khavelun/Desktop/config.dot")

    ground.i_int.invoke(1000)
    ground.i_int.invoke(2000)
    ground.i_int.invoke(3000)
  }
}


