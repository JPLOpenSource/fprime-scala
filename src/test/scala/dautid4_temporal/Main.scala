package dautid4_temporal

import dautid._
import dautid.Util.time

/**
 * Property OneLockGlobally: If a thread take a lock, then no thread can take a lock
 * until the lock has been released.
 */

trait LockEvent
case class acquire(t:Int, x:Int) extends LockEvent
case class release(t:Int, x:Int) extends LockEvent

class OneLockGlobally extends Monitor[LockEvent] {
  always {
    case acquire(t, x) =>
      watch {
        case acquire(_,_) => error
        case release(`t`,`x`) => ok
      } label(t,x)
  }
}

object Main {
  def main(args: Array[String]) {
    val INDEX = 1000000
    DautOptions.DEBUG = false
    val m = new OneLockGlobally
    time (s"analyzing $INDEX acquisitions") {
      for (index <- 1 to INDEX) {
        m.verify(acquire(index, index))
        m.verify(release(index, index))
      }
    }
    m.end()
  }
}


