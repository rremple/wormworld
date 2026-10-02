package wormworld

case class Synapse(
  from: String,
  to: String,
  weight: Double,
  isGapJunction: Boolean
):
  def reversed: Synapse = copy(from = to, to = from)

object Synapse:
  // A scaling factor to tune how strongly chemical synapses affect the voltage
  val synapticStrength: Double = 0.4 // equilibrium preventing firing at 0.2

  // A scaling factor to tune how strongly electrical synapses affect the voltage
  val electricalCoupling: Double = synapticStrength // 0.21

  def chemical(from: String, to: String, weight: Double): Synapse =
    Synapse(from, to, weight, isGapJunction = false)

  // Model ohmic gap junctions as two directed edges for network flow analysis
  def electrical(cellA: String, cellB: String, weight: Double): Synapse =
    Synapse(cellA, cellB, weight, isGapJunction = true)
