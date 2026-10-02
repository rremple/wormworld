package wormworld

import scala.util.Random

case class Connection(id: String, weight: Double):
  override def toString: String = s"$id ($weight)"

case class Neuron(
  id: String,
  fromChemical: Vector[Connection],
  toChemical: Vector[Connection],
  electrical: Vector[Connection]
):
  private def format[T](s: Vector[T]): String =
    if s.isEmpty then "nowhere" else if s.size == 1 then s.head.toString else s.mkString("{", ", ", "}")

  override def toString: String = s"Neuron $id:\n" +
    s"   + chemical connections from ${format(fromChemical)} and to ${format(toChemical)}\n" +
    s"   + electrical connections to/from ${format(electrical)}"

object Neuron:

  case class State(neuron: Neuron, voltage: Double, lastFired: Int):
    def updated(newVoltage: Double, newLastFired: Int): State =
      this.copy(voltage = newVoltage, lastFired = newLastFired)

    def withVoltage(newVoltage: Double): State =
      this.copy(voltage = newVoltage)

  object State:
    def initial(neuron: Neuron) = State(neuron, Neuron.initVoltage, -Neuron.actionPotentialSteps - 1)
  
  val random = Random()

  /**
   * A neuron placed on the screen
   *
   * @param neuron
   * thing to draw
   * @param x
   * value from 0.0 to 1.0
   * @param y
   * value from 0.0 to 1.0
   */
  case class Placed(neuron: Neuron, x: Double, y: Double):
    def id: String = neuron.id

    def withY(newY: Double): Placed = this.copy(y = newY)

  object Placed:
    def apply(neuron: Neuron, x: Double): Placed =
      val y = random.nextDouble() // random seems good enough
      Placed(neuron, x, y)

  val minVoltage: Double = -70.0
  val initVoltage: Double = -45.0
  val restVoltage: Double = -50.0 // leakReversal
  val thresholdVoltage: Double = -20.0
  val centerVoltage: Double = (thresholdVoltage + restVoltage) / 2.0 + 4.0 // between rest and spike -31 mV
  val maxVoltage: Double = 40.0

  val capacitance: Double = 1.0
  val leakConductance: Double = 0.1
  val chemicalConductance = 0.5
  val gapJunctionConductance = 0.0141 // ~math.sqrt(2) / 100.0

  // The absolute maximum amount of current the entire cell membrane can withstand before all of its available ion
  // channels are completely saturated (+/-)
  val maxChemicalCurrent = 15.0

  // 5 ms (50 steps) action potential curve from a cubic spline at these points:
  // - (0.0, -20.0): starts at the threshold voltage
  // - (10.0, 40.0): rises quickly (~1 ms) to the max voltage
  // - (20.0, -14.0), (30.0, -63.0): sinks steadily (~2.5 ms) down to min voltage (clipped)
  // - (50.0, -50.0): stabilizes slowly (~1.5 ms) back to rest voltage
  val actionPotentialWave: IndexedSeq[Double] = IndexedSeq[Double](
    -11.01, -2.20, 6.24, 14.14, 21.32, 27.59, 32.78, 36.70, 39.16, 40.00,

    39.09, 36.60, 32.73, 27.72, 21.78, 15.15, 8.03, 0.65, -6.77, -14.00, -20.86, -27.31, -33.33, -38.93, -44.08, -48.79,
    -53.05, -56.84, -60.16, -63.00, -65.36, -67.26, -68.71, -69.76, -70.00, -70.00,

    -70.00, -70.00, -69.61, -68.68, -67.51, -66.12, -64.53, -62.77, -60.86, -58.84, -56.71, -54.52, -52.27, -50.00
  )

  val actionPotentialSteps: Int = actionPotentialWave.size
