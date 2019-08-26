package example13_camera

/**
 * The imaging system described in the paper:
 * "Modeling and Monitoring of Hierarchical State Machines in Scala",
 * Klaus Havelund and Rajeev Joshi,
 * 9th International Workshop on Software Engineering for Resilient Systems (SERENE 2017),
 * September 4-5, 2017, Geneva, Switzerland. Lecture Notes in Computer Science.
 */

import akka.actor.ReceiveTimeout
import fprime._
import hsm._
import daut._

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

  object Machine extends HSM[Any] {
    var duration: Int = 0
    val DARK_THRESHOLD = 5

    def getTemp(): Int = 15

    states(off, on, powering, exposing, exposing_light, exposing_dark, saving)
    initial(off)

    object off extends state() {
      entry {
        println("*** ENTERING off STATE")
      }
      when {
        case TakeImage(d) => on exec {
          println(s"@@@@@@ RECEIVING TakeImage($d)")
          o_obs.logEvent(EvrTakeImage(d))
          duration = d
          o_cam.invoke(PowerOn)
        }
      }
    }

    object on extends state() {
      entry {
        println("*** ENTERING on STATE")
      }
      when {
        case ShutDown => off exec {
          o_obs.logEvent(EvrImageAborted)
          o_cam.invoke(PowerOff)
        }
      }
    }

    object powering extends state(on, true) {
      entry {
        println("*** ENTERING powering STATE")
      }
      when {
        case Ready => exposing
      }
    }

    object exposing extends state(on) {
      entry {
        println("*** ENTERING exposing STATE")
      }
    }

    object exposing_light extends state(exposing, true) {
      entry {
        println("*** ENTERING exposing_light STATE")
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
        println("*** ENTERING exposing_dark STATE")
        setTimer(duration)
      }
      when {
        case ReceiveTimeout => saving
      }
    }

    object saving extends state(on) {
      entry {
        println("*** ENTERING saving STATE")
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
    case input =>
      if (!Machine(input)) {
         selfTrigger(input)
      }
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

class Verifier extends Component {
  val i_obs = new ObsInput
  val o_obs = new ObsOutput

  object SaveOrAbort extends Monitor[Observation] {
    always {
      case EvrTakeImage(_) => hot {
        case EvrImageSaved | EvrImageAborted => ok
        case EvrTakeImage(_) => error("Image was not saved or aborted")
      }
    }
  }

  override def when: PartialFunction[Any, Unit] = {
    case obs: Observation => SaveOrAbort.verify(obs)
  }
}

class Ground extends Component {
  val i_int = new Input[Int]
  val i_obs = new ObsInput
  val o_cmd = new CommandOutput

  override def when: PartialFunction[Any, Unit] = {
    case d: Int => o_cmd.invoke(TakeImage(d))
    case o: Observation =>
      println(s"===> observation: $o")
  }
}

// Main:

object Main {
  def main(args: Array[String]): Unit = {
    val imaging = new Imaging
    val camera = new Camera
    val verifier = new Verifier
    val ground = new Ground

    imaging.o_cam.connect(camera.i_img)
    imaging.o_obs.connect(ground.i_obs)
    imaging.o_obs.connect(verifier.i_obs)
    camera.o_img.connect(imaging.i_cam)
    camera.o_obs.connect(ground.i_obs)
    camera.o_obs.connect(verifier.i_obs)
    verifier.o_obs.connect(ground.i_obs)
    ground.o_cmd.connect(imaging.i_cmd)

    Configuration.show("/Users/khavelun/Desktop/config.dot")

    ground.i_int.invoke(1)
    println("================ GETTING PAST FIRST IMAGE COMMAND")
    ground.i_int.invoke(1)
    println("================ GETTING PAST SECOND IMAGE COMMAND")
  }
}


