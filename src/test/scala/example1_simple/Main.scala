package example1_simple

/**
 * Simple component-based system.
 */

import fprime._

// === A ===

class A extends Component {
  var in = new Input[Int]
  var out = new Output[Int]
  var outSync = new SyncOutput[Int,Int]

  var counter : Int = 0

  def when = {
    case x:Int =>
      counter += x
      println(s"A : $counter")
      out.invoke(100)
      val result = outSync.invoke(counter)
      println(s"A return: $result")
    case _ => ()
  }
}

// === B ===

class B extends Component {
  var in = new Input[Int]
  var inSync = new SyncInput[Int,Int] {
    override def invoke(a: Int): Int = negate(a)
  }
  var outBool = new Output[Boolean]
  var outInt1 = new Output[Int]
  var outInt2 = new Output[Int]

  var counter : Int = 0

  def negate(x : Int): Int = -x

  def when = {
    case x:Int =>
      counter += x
      println(s"B : $counter")
      outBool.invoke(x % 2 == 0)
      outInt1.invoke(x + 100)
      outInt2.invoke(x + 100)
    case _ => ()
  }
}

// === C ===

trait Message extends Serializable
case class Bool(x: Boolean) extends Message
case class Int1(x:Int) extends Message
case class Int2(x:Int) extends Message

class C extends Component {
  var inBool = new Input[Boolean] {
    override def invoke(a : Boolean) = {
      invokeAny(Bool(a))
    }
  }
  var inInt1 = new Input[Int] {
    override def invoke(a: Int): Unit = {
      invokeAny(Int1(a))
    }
  }
  var inInt2 = new Input[Int] {
    override def invoke(a: Int): Unit = {
      invokeAny(Int2(a))
    }
  }

  var counter : Int = 0

  def when = {
    case Bool(b) =>
      println(s"C it is even?: $b")
    case Int1(x) =>
      counter += x
      println(s"C : $counter")
    case Int2(x) =>
      counter += x
      println(s"C : $counter")
    case _ => ()
  }
}

/******
 * Main
 ******/

object Main {
  def main(args : Array[String]): Unit = {
    val a = new A
    val b = new B
    val c = new C

    implicit val componentName: String = "main"

    val main_out = new Output[Int]

    main_out.connect(a.in)
    a.out.connect(b.in)
    a.outSync.connect(b.inSync)
    b.outBool.connect(c.inBool)
    b.outInt1.connect(c.inInt1)
    b.outInt2.connect(c.inInt2)

    main_out.invoke(42)
  }
}
