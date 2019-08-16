package example13_camera

/**
 * The imaging system described in the paper:
 * "Modeling and Monitoring of Hierarchical State Machines in Scala",
 * Klaus Havelund and Rajeev Joshi,
 * 9th International Workshop on Software Engineering for Resilient Systems (SERENE 2017),
 * September 4-5, 2017, Geneva, Switzerland. Lecture Notes in Computer Science.
 */

import fprime._
import hsm._
import scala.language.postfixOps

// Events:

case class TakeImage(d: Int) extends Command

trait Imaging2Camera
case object POWER_ON extends Imaging2Camera
case object POWER_OFF extends Imaging2Camera
case object SAVE_DATA extends Imaging2Camera

trait Camera2Imaging
case object READY extends Camera2Imaging

case object EVR_POWER_ON extends Event
case object EVR_POWER_OFF extends Event
case object EVR_SAVE_DATA extends Event

// Components:

class Imaging extends Component {
  val i_cmd = new CommandInput
  val i_cam = new Input[Camera2Imaging]
  val o_cam = new Output[Imaging2Camera]
  val o_obs = new ObsOutput

  object Machine extends HSM[Any] {
    states(off,on,powering,exposing,exposingLight,exposingDark,saving)

    initial(off)

    object off extends state() {
      when {
        case _ => stay
      }
    }

    object on extends state() {
      when {
        case _ => stay
      }
    }

    object powering extends state(on, true) {
      when {
        case _ => stay
      }
    }

    object exposing extends state(on) {
      when {
        case _ => stay
      }
    }

    object exposingLight extends state(exposing, true) {
      when {
        case _ => stay
      }
    }

    object exposingDark extends state(exposing) {
      when {
        case _ => stay
      }
    }

    object saving extends state(on) {
      when {
        case _ => stay
      }
    }
  }

  override def when: PartialFunction[Any, Unit] = {
    case input => Machine(input)
  }
}

class Camera extends Component {
  val i_img = new Input[Imaging2Camera]
  val o_img = new Output[Camera2Imaging]
  val o_obs = new ObsOutput

  override def when: PartialFunction[Any, Unit] = {
    case POWER_ON =>
      o_obs.invoke(EVR_POWER_ON)
      o_img.invoke(READY)
    case POWER_OFF =>
      o_obs.invoke(EVR_POWER_OFF)
      o_img.invoke(READY)
    case SAVE_DATA =>
      o_obs.invoke(EVR_SAVE_DATA)
      o_img.invoke(READY)
  }
}

class Ground extends Component {
  val i_int = new Input[Int]
  val i_obs = new ObsInput
  val o_cmd = new CommandOutput

  override def when: PartialFunction[Any, Unit] = {
    case d : Int => o_cmd.invoke(TakeImage(d))
    case o : Observation => println(s"observation: $o")
  }
}

// Main:

object Main {
  def main(args: Array[String]): Unit = {
    val imaging = new Imaging
    val camera = new Camera
    val ground = new Ground

    imaging.o_cam.connect(camera.i_img)
    imaging.o_obs.connect(ground.i_obs)
    camera.o_img.connect(imaging.i_cam)
    camera.o_obs.connect(ground.i_obs)
    ground.o_cmd.connect(imaging.i_cmd)

    ground.i_int.invoke(10)
  }
}


