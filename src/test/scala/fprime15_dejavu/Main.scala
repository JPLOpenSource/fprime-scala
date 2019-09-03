package fprime15_dejavu

import dejavu.Verify

object Main {
  def main(args: Array[String]): Unit = {
    val PATH = "/Users/khavelun/Desktop/development/ideaworkspace/fprime/src/test/scala/example15_dejavu"
    val spec1 = s"$PATH/spec1.txt"
    val log1 = s"$PATH/log1.csv"
    val log2 = s"$PATH/log2.csv"

    Verify.main(Array(spec1,log2, "3"))
  }
}
