package fprime3_rust

/**
 * Rust Example. Modeled without case class message types,
 * which are not really needed. example2 is done with these.
 */

import fprime._

/******
 * Util
 ******/

object Util {
  type u32 = Int
}
import Util._

/************
 * Components
 ************/

// === A ===

class A extends Component {
  val u32_in = new Input[u32]
  val u32_out = new SyncOutput[u32,Unit]

  var u32_val : u32 = 0

  def u32_in_handler(arg : u32): Unit = {
    println(s"a.u32_in_handler called with argument $arg")
    u32_val = arg
    u32_out.invoke(arg)
  }

  def when = {
    case x: u32 => u32_in_handler(x)
  }
}

// === B ===

class B extends Component {
  val string_in = new Input[String]
  val string_out = new Output[String]

  var string_val : String = ""

  def string_in_handler(arg: String) = {
    println(s"""b.string_in_handler called with argument "$arg" """)
    string_val = arg
    string_out.invoke(arg)
  }

  def when = {
    case arg: String => string_in_handler(arg)
  }
}

// === C ===

class C extends Component {
  val u32_in = new Input[u32]
  val string_in = new Input[String]

  var u32_val : u32 = 0
  var string_val : String = ""

  def u32_in_handler(arg:u32): Unit = {
    println(s"c.u32_in_handler called with argument $arg")
    u32_val = arg
  }

  def string_in_handler(arg: String): Unit = {
    println(s"""c.string_in_handler called with argument \"$arg\" """)
    string_val = arg
  }

  def when = {
    case arg: u32 => u32_in_handler(arg)
    case arg: String => string_in_handler(arg)
  }
}

// === D ===

class D extends PassiveComponent {
  val u32_in = new GuardedSyncInput[u32,Unit] {
    override def invokeGuarded(a: u32): Unit = {
      u32_in_handler(a)
    }
  }

  var u32_val : u32 = 0

  def u32_in_handler(arg : u32): Unit = {
    println(s"d.u32_in_handler called with argument $arg")
    u32_val = arg
  }
}

/******
 * Main
 ******/

object Main {
  def main(args: Array[String]): Unit = {
    println("begin")

    val a = new A
    val b = new B
    val c = new C
    val d = new D

    b.string_out.connect(c.string_in)
    a.u32_out.connect(d.u32_in)

    var i = 0
    while (true) {
      a.u32_in.invoke(i)
      val s = i.toString
      b.string_in.invoke(s)
      i += 1
      Thread.sleep(1000)
    }

    println("end")
  }
}

