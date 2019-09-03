package fprime5_serialization

/**
 * Testing Serializabile transfer.
 */

import fprime._

class Data(x: Int, s: String) extends Serializable {
  override def toString  = s"""Data($x,"$s")"""
}

class A extends Component {
  val in = new Input[Int]
  val out = new Output[Data]

  def when = {
    case x: Int =>
      val data = new Data(x, x.toString + " seconds")
      println(s"A inserts $data")
      out.invoke(data)
  }
}

class B extends Component {
  val in = new Input[Data]

  def when = {
    case data : Data => println(s"B picks up $data")
  }
}

object Main {
  def main(args: Array[String]): Unit = {
    val a = new A
    val b = new B

    a.out.connect(b.in)

    a.in.invoke(42)
  }
}

