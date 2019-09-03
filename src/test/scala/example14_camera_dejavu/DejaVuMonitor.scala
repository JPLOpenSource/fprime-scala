package example14_camera_dejavu

import qtl.{Formula, Monitor, V}

// This monitor below is auto generated using package example15_dejavu
// as follows:
//
// 1. Run main program on property.
// 2. Locate TraceMonitor.scala and select code for formula (see below).
// 3. copy and paste into this file.

// --- monitor begin ---

/*
prop fifo : Forall d1 . Forall d2 . (takeImage(d2) & P (requestImage(d2) & @ P requestImage(d1))) -> @ P takeImage(d1)
*/

class Formula_fifo(monitor: Monitor) extends Formula(monitor) {

  val var_d1 :: var_d2 :: Nil = declareVariables(("d1", false), ("d2", false))

  override def evaluate(): Boolean = {
    now(4) = build("takeImage")(V("d2"))
    now(7) = build("requestImage")(V("d2"))
    now(10) = build("requestImage")(V("d1"))
    now(13) = build("takeImage")(V("d1"))
    now(9) = now(10).or(pre(9))
    now(8) = pre(9)
    now(6) = now(7).and(now(8))
    now(5) = now(6).or(pre(5))
    now(3) = now(4).and(now(5))
    now(12) = now(13).or(pre(12))
    now(11) = pre(12)
    now(2) = now(3).not().or(now(11))
    now(1) = now(2).forAll(var_d2.quantvar)
    now(0) = now(1).forAll(var_d1.quantvar)

    debugMonitorState()

    val error = now(0).isZero
    if (error) monitor.recordResult()
    tmp = now
    now = pre
    pre = tmp
    touchedByLastEvent = emptyTouchedSet
    !error
  }

  varsInRelations = Set()
  val indices: List[Int] = List(11, 12, 5, 8, 9)

  pre = Array.fill(14)(bddGenerator.False)
  now = Array.fill(14)(bddGenerator.False)

  txt = Array(
    "Forall d1 . Forall d2 . (takeImage(d2) & P (requestImage(d2) & @ P requestImage(d1))) -> @ P takeImage(d1)",
    "Forall d2 . (takeImage(d2) & P (requestImage(d2) & @ P requestImage(d1))) -> @ P takeImage(d1)",
    "(takeImage(d2) & P (requestImage(d2) & @ P requestImage(d1))) -> @ P takeImage(d1)",
    "takeImage(d2) & P (requestImage(d2) & @ P requestImage(d1))",
    "takeImage(d2)",
    "P (requestImage(d2) & @ P requestImage(d1))",
    "requestImage(d2) & @ P requestImage(d1)",
    "requestImage(d2)",
    "@ P requestImage(d1)",
    "P requestImage(d1)",
    "requestImage(d1)",
    "@ P takeImage(d1)",
    "P takeImage(d1)",
    "takeImage(d1)"
  )

  debugMonitorState()
}

/* The specialized Monitor for the provided properties. */

class PropertyMonitor extends Monitor {
  def eventsInSpec: Set[String] = Set("takeImage", "requestImage")

  formulae ++= List(new Formula_fifo(this))
}

// --- monitor end ---
