package dautid1_temporal

import dautid._
import dautid.Util.time

/**
 * Property AcquireRelease: A task acquiring a lock should eventually release it. A task can acquire at most
 * one lock at a time.
 */

trait LockEvent
case class acquire(t:Int, x:Int) extends LockEvent
case class release(t:Int, x:Int) extends LockEvent

class AcquireRelease extends Monitor[LockEvent] {
  case class Ok(t: Int, x: Int) extends state

  override def keyOf(event: LockEvent): Option[Int] = {
    event match {
      case acquire(t, _) => Some(t)
      case release(t, _) => Some(t)
    }
  }

  always {
    case acquire(t, x) =>
      hot {
        case acquire(`t`,_) => error
        case release(`t`,`x`) => Ok(t,x)
      } label(t,x)
  }
}

object Main {
  def main(args: Array[String]) {
    val INDEX = 100000
    DautOptions.DEBUG = false
    val m = new AcquireRelease
    time (s"analyzing $INDEX acquisitions") {
      for (index <- 1 to INDEX) {
        m.verify(acquire(index, index))
      }
      for (index <- 1 to INDEX) {
        m.verify(release(index, index))
      }
    }
    m.end()
  }
}

