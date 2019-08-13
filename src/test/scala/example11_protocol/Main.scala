package example11_protocol

/**
 * The bounded re-transmission protocol.
 */

import Util._
import akka.actor.Actor.Receive
import akka.actor.ReceiveTimeout
import daut.Monitor
import fprime._
import hsm._

object Util {
  val random = scala.util.Random

  type Data = Int
  type File = List[Data]

  def confToEvr(conf: Confirmation): Evr = {
    conf match {
      case Ok => Ok_Evr
      case NotOk => NotOk_Evr
      case DontKnow => DontKnow_Evr
    }
  }

  def randomBoolProb(p: Int): Boolean = {
    val nr = random.nextInt(100) + 1
    nr <= p
  }

  def verify(cond: Boolean, str: String) {
    if (!cond) {
      println()
      println("=========================================================")
      println("- - - - - - - - - - - - - - - - - - - - - - - - - - - - -")
      println(s"********** $str **********")
      println("- - - - - - - - - - - - - - - - - - - - - - - - - - - - -")
      println("=========================================================")
      println()
      assert(false, "going down on verification error!")
    }
  }
}

/** *************
 * Message types
 * **************/

// P -> S:

case class Request(file: File)

// S -> K & K -> R:

case class Message(first: Boolean, last: Boolean, toggle: Boolean, data: Data)

// R -> C:


trait Delivery

case object IndErr extends Delivery

case class Ind(data: Data, which: Which) extends Delivery

trait Which

case object First extends Which

case object Last extends Which

case object Incomplete extends Which

// Self triggering

case object STEP

// Receiver -> L:

case class AckRL()

// L -> Sender:

case class AckLS()

// Sender -> Producer:

trait Confirmation

case object Ok extends Confirmation

case object NotOk extends Confirmation

case object DontKnow extends Confirmation

// Monitors:

trait Evr

case class Request_Evr(file: File) extends Evr

case class Ind_Evr(data: Data, which: Which) extends Evr

case object IndErr_Evr extends Evr

case object Ok_Evr extends Evr

case object DontKnow_Evr extends Evr

case object NotOk_Evr extends Evr

/** *******
 * Observer
 * ********/

class Monitor1 extends Component {
  val i_evr = new Input[Evr]

  var busy: Boolean = false
  var file: File = Nil
  var last: Int = 0
  var nil: Int = last + 1
  var head: Int = nil
  var first: Boolean = true
  var error: Boolean = false

  override def when: Receive = {
    case Request_Evr(vector) =>
      verify(!busy, "Request when observer is busy")
      busy = true
      file = vector
      head = 0
      last = file.size - 1
      nil = last + 1
    case Ind_Evr(data, which) =>
      verify(
        busy && !error && head != nil &&
          data == file(head) &&
          (which match {
            case First => head != last && first
            case Incomplete => head != last && !first
            case Last => head == last
          }),
        "Delivery of result not ok"
      )
      first = head == last
      head += 1
    case IndErr_Evr =>
      verify(error, "Indication error")
      first = true
      error = false
    case Ok_Evr =>
      verify(busy && !error && head == nil, "Ok error")
      busy = false
      error = !first
      head = nil
    case DontKnow_Evr =>
      verify(busy && !error && (head == nil || head == last), "DontKnow error")
      busy = false
      error = !first
      head = nil
    case NotOk_Evr =>
      verify(busy && !error && head != nil, "NotOk error")
      busy = false
      error = !first
      head = nil
  }
}

/******************
 * Temporal monitor
 ******************/

class Monitor2 extends Component {
  val i_evr = new Input[Evr]

  object M1 extends Monitor[Evr] {
    always {
      case Request_Evr(_) => watch {
        case IndErr_Evr => watch {
          case Ok_Evr => error
          case Request_Evr(_) => ok
        }
      }
    }
  }

  object M2 extends Monitor[Evr] {
    always {
      case Request_Evr(_) => watch {
        case Request_Evr(_) => error // TODO: is never executed
      }
    }
  }

  override def when: Receive = {
    case evr : Evr =>
      M1.verify(evr)
      M2.verify(evr)
  }
}

/*
Request_Evr(file: File)
Ind_Evr(data: Data, which: Which)
IndErr_Evr
Ok_Evr
DontKnow_Evr
NotOk_Evr
 */

/** ********
 * Producer
 * *********/

class Producer extends Component {
  val i_conf = new Input[Confirmation]
  val o_req = new Output[Request]

  val r = scala.util.Random

  def newFile(): File = {
    val size = r.nextInt(10) + 1
    var file: File = Nil
    for (_ <- 0 to size) {
      val value = r.nextInt(100)
      file ++= List(value)
    }
    file
  }

  override def when: Receive = {
    case conf =>
      val file = newFile()
      println("------------------------------------------------------------------")
      println(file)
      println("------------------------------------------------------------------")
      o_req.invoke(Request(file))
  }
}

/** ******
 * Sender
 * *******/

class Sender(max_tries: Int, timer1: Int, timer2: Int) extends Component {
  val i_req = new Input[Request]
  val i_acq = new Input[AckLS]
  val o_conf = new Output[Confirmation]
  val o_msg = new Output[Message]
  val o_evr = new Output[Evr]

  var file: File = Nil
  var last: Int = 0
  var nil: Int = last + 1
  var head: Int = nil
  var first: Boolean = true
  var toggle: Boolean = false
  var rn: Int = 0

  object Machine extends HSM[Any] {
    states(WR, SF, WA, SC, WT2)

    initial(WR)

    object WR extends state() {
      when {
        case Request(vector) => SF exec {
          o_evr.invoke(Request_Evr(vector))
          file = vector
          head = 0
          last = file.size - 1
          nil = last + 1
        }
      }
    }

    object SF extends state() {
      entry {
        selfTrigger(STEP)
      }

      when {
        case STEP => WA exec {
          val msg = Message(first, head == last, toggle, file(head))
          rn += 1
          o_msg.invoke(msg)
          setTimer(timer1)
        }
      }
    }

    object WA extends state() {
      when {
        case AckLS() => (if (head == last) SC else SF) exec {
          first = (head == last)
          if (head != last) {
            rn = 0
          }
          head += 1
          toggle = !toggle
        }
        case ReceiveTimeout => (if (rn == max_tries) SC else SF) exec {
          println("---> timeout 1 in sender")
        }
      }
    }

    object SC extends state() {
      entry {
        selfTrigger(STEP)
      }

      when {
        case STEP => (if (head == nil) WR else WT2) exec {
          val conf: Confirmation =
            if (head == nil)
              Ok
            else if (head == last && rn != 0)
              DontKnow
            else
              NotOk
          o_evr.invoke(confToEvr(conf))
          o_conf.invoke(conf)
          if (head != nil) {
            first = true
            toggle = !toggle
            head = nil
            setTimer(timer2) // going to state WT2
          }
          rn = 0
        }
      }
    }

    object WT2 extends state() {
      when {
        case ReceiveTimeout => WR exec {
          println("---> timeout 2 in sender")
        }
      }
    }

  }

  override def when: Receive = {
    case input => Machine(input)
  }
}

/** ********
 * Receiver
 * *********/

class Receiver(timer: Int) extends Component {
  val i_msg = new Input[Message]
  val o_delivery = new Output[Delivery]
  val o_ack = new Output[AckRL]
  val o_evr = new Output[Evr]

  var first: Boolean = true
  var toggle: Boolean = false
  var ctoggle: Boolean = false
  var timerOn: Boolean = true
  var msg: Message = Message(first = false, last = false, toggle = false, data = 0)


  object Machine extends HSM[Any] {
    states(WF, SI, SA, RTS, NOK)

    initial(WF)

    object WF extends state() {
      entry {
        if (timerOn) setTimer(timer)
      }

      when {
        case message: Message if (!ctoggle || (message.toggle == toggle)) => SI exec {
          msg = message
          timerOn = false
        }
        case message: Message => RTS exec {
          msg = message
        }
        case ReceiveTimeout if !first => NOK exec {
          println("---> timeout in receiver")
          ctoggle = false
          timerOn = false
        }
        case ReceiveTimeout => WF exec {
          println("---> timeout in receiver")
          ctoggle = false
        }
      }
    }

    object SI extends state() {
      entry {
        selfTrigger(STEP)
      }

      when {
        case STEP => SA exec {
          val indication: Which =
            if (msg.last)
              Last
            else if (msg.first)
              First
            else
              Incomplete
          o_evr.invoke(Ind_Evr(msg.data, indication))
          o_delivery.invoke(Ind(msg.data, indication))
          first = msg.last
          ctoggle = true
          toggle = !msg.toggle
        }
      }
    }

    object SA extends state() {
      entry {
        selfTrigger(STEP)
      }

      when {
        case STEP => WF exec {
          o_ack.invoke(AckRL())
          timerOn = true
        }
      }
    }

    object RTS extends state() {
      entry {
        selfTrigger(STEP)
      }

      when {
        case STEP => WF exec {
          o_ack.invoke(AckRL())
        }
      }
    }

    object NOK extends state() {
      entry {
        selfTrigger(STEP)
      }

      when {
        case STEP => WF exec {
          o_evr.invoke(IndErr_Evr)
          o_delivery.invoke(IndErr)
          first = true
          timerOn = true
        }
      }
    }

  }

  override def when: Receive = {
    case input => Machine(input)
  }
}

/** ********
 * Consumer
 * *********/

class Consumer extends Component {
  val i_delivery = new Input[Delivery]

  override def when: Receive = {
    case _ =>
  }
}

/** *********
 * K Channel
 * **********/

class KChannel(reliability: Int) extends Component {
  val i_msg = new Input[Message]
  val o_msg = new Output[Message]

  override def when: Receive = {
    case msg: Message =>
      randomBoolProb(reliability) match {
        case true =>
          o_msg.invoke(msg)
        case false => {
          println(s"*** Chanel K lost message: $msg")
        }
      }
  }
}

/** *********
 * L Channel
 * **********/

class LChannel(reliability: Int) extends Component {
  val i_ack = new Input[AckRL]
  val o_ack = new Output[AckLS]

  override def when: Receive = {
    case _ =>
      randomBoolProb(reliability) match {
        case true =>
          o_ack.invoke(AckLS())
        case false =>
          println("*** Channel L lost ack");
      }
  }
}

/******
 * Main
 ******/

object Main {
  def main(args: Array[String]): Unit = {
    val rel_k : Int = 80
    val rel_l : Int = 80
    val max_retries : Int = 3

    // val (s_timer1, s_timer2, r_timer) = (1,3,4)
    val (s_timer1, s_timer2, r_timer) = (3,8,10)

    val producer = new Producer
    val sender = new Sender(max_retries, s_timer1, s_timer2)
    val receiver = new Receiver(r_timer)
    val consumer = new Consumer
    val kchannel = new KChannel(rel_k)
    val lchannel = new LChannel(rel_l)
    val monitor1 = new Monitor1
    val monitor2 = new Monitor2

    producer.o_req.connect(sender.i_req)
    sender.o_conf.connect(producer.i_conf)
    sender.o_msg.connect(kchannel.i_msg)
    sender.o_evr.connect(monitor1.i_evr)
    // sender.o_evr.connect(monitor2.i_evr)
    receiver.o_delivery.connect(consumer.i_delivery)
    receiver.o_ack.connect(lchannel.i_ack)
    receiver.o_evr.connect(monitor1.i_evr)
    // receiver.o_evr.connect(monitor2.i_evr)
    kchannel.o_msg.connect(receiver.i_msg)
    lchannel.o_ack.connect(sender.i_acq)

    producer.i_conf.invoke(Ok)
  }
}
