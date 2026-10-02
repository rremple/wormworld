package wormworld

import intervalidus.DimensionalFunctionBase.ValidFunction
import intervalidus.DiscreteValue.given
import intervalidus.Domain
import intervalidus.Interval1D.{interval, intervalAt, intervalFrom}
import intervalidus.immutable.DataFunction

import scala.language.implicitConversions

object Simulator:
  val dt: Double = 0.1 // Time step in milliseconds

  case class Config(
    simulationName: String = "wormworld",
    stimuli: Map[String, DataFunction.In1D[Double, Int]] = Map.empty,
    updateState: (state: Map[String, Neuron.State], step: Int) => Map[String, Neuron.State] = (s, _) => s,
    notesById: Map[String, Int] = Map.empty,
    useBelowRest: Boolean = false,
    centerThreshold: Int = 80, // higher threshold for "percussive" instrument
    centerLeftPrefix: Option[String] = None,
    centerRightPrefix: Option[String] = None,
    refreshEvery: Int = 1, // steps
    totalSteps: Int = 1000, // total steps
    frameDelay: Long = 50 // ms = 20 fps
  )

  // reusable stimulus functions

  // starts and ends at rest voltage
  def triangleWave(startStep: Int, peakVoltage: Double, duration: Int): Seq[ValidFunction[Double, Domain.In1D[Int]]] =
    val peakStep = duration / 2 + startStep
    val endStep = startStep + duration
    val voltageStep = (peakVoltage - Neuron.restVoltage) / duration * 2.0 // ramp up then down

    def rise(stepDomain: Domain.In1D[Int]): Double = stepDomain match
      case Domain.Point.In1D(step) => Neuron.restVoltage + voltageStep * (step - startStep) // ramp up
      case theUnexpected           => throw Exception(s"Didn't expect $theUnexpected")

    def fall(stepDomain: Domain.In1D[Int]): Double = stepDomain match
      case Domain.Point.In1D(step) => Neuron.restVoltage + voltageStep * (endStep - step) // ramp down
      case theUnexpected           => throw Exception(s"Didn't expect $theUnexpected")

    Seq(interval(startStep, peakStep) -> rise, interval(peakStep + 1, startStep + duration) -> fall)

  // if it ends, ends at rest voltage
  def squareWave(
    startStep: Int,
    voltage: Double,
    duration: Option[Int] = None
  ): Seq[ValidFunction[Double, Domain.In1D[Int]]] =
    def constant(value: Double): (stepDomain: Domain.In1D[Int]) => Double = _ => value

    duration.map(_ + startStep) match
      case Some(endStep) =>
        Seq(
          intervalFrom(startStep).toBefore(endStep) -> constant(voltage),
          intervalAt(endStep) -> constant(Neuron.restVoltage)
        )
      case None => Seq(intervalFrom(startStep) -> constant(voltage))

class Simulator(
  chemicalEdges: Seq[Synapse],
  electricalEdges: Seq[Synapse],
  config: Simulator.Config
):
  import Simulator.dt

  val chemicalFrom = chemicalEdges.groupBy(_.from)
  val chemicalTo = chemicalEdges.groupBy(_.to)
  val electrical = electricalEdges.groupBy(_.from) // data contains (A, B) and (B, A) pairs

  val allSynapses = chemicalEdges ++ electricalEdges

  // Derive neurons from chemical and electrical synapses
  // Note that CANL, CANR, and VC6 are excluded because they have no connections (so 299 instead of 302)
  val allNeurons = (allSynapses.map(_.from) ++ allSynapses.map(_.to)).distinct.map: id =>
    Neuron(
      id = id,
      fromChemical = chemicalTo.getOrElse(id, Seq.empty).map(s => Connection(s.from, s.weight)).toVector,
      toChemical = chemicalFrom.getOrElse(id, Seq.empty).map(s => Connection(s.to, s.weight)).toVector,
      electrical = electrical.getOrElse(id, Seq.empty).map(s => Connection(s.to, s.weight)).toVector
    )
  val neuronById = allNeurons.map(n => n.id -> n).toMap

  val filePrefix = config.simulationName.toLowerCase.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-")
  val visualizer = Visualizer(config, filePrefix, allNeurons)
  visualizer.relaxAll()
  visualizer.spreadAll()

  val sonifier = Sonifier(config, filePrefix)

  val initialState: Map[String, Neuron.State] = allNeurons.map(n => n.id -> Neuron.State.initial(n)).toMap

  visualizer.renderNextFrame(initialState, useCachedBuffer = false)

  def logistic(v: Double): Double =
    if v <= Neuron.restVoltage then 0.0 // Clips at the biological resting floor
    else 1.0 / (1.0 + math.exp(-0.5 * (v - Neuron.centerVoltage))) // k = 0.5 dictates the steepness of the curve

  def weightScale(i: Int): Double = math.sqrt(i max 1) // for homeostatic scaling

  def stepPhysics(step: Int, currentState: Map[String, Neuron.State]): Map[String, Neuron.State] =
    currentState.map: (id, state) =>
      val neuron = state.neuron
      val stepsSinceLastFired = step - state.lastFired

      val updatedState = if stepsSinceLastFired <= Neuron.actionPotentialSteps then
        // Fix voltage according to the action potential wave values after firing
        state.withVoltage(Neuron.actionPotentialWave(stepsSinceLastFired - 1))
      else
        // Calculate the baseline leak
        val iLeak = (state.voltage - Neuron.restVoltage) * Neuron.leakConductance

        // Calculate the Electrical Current (multiplied by its coupling scaler)
        val iElectrical = neuron.electrical
          .map(c => c.weight * (currentState(c.id).voltage - state.voltage))
          .sum / weightScale(neuron.electrical.size) * Synapse.electricalCoupling

        // Calculate the Chemical Current (fixed threshold multiplied by its strength scaler)
        val iChemicalUncapped = neuron.fromChemical
          .map(c => Neuron.maxChemicalCurrent * c.weight * logistic(currentState(c.id).voltage))
          .sum / weightScale(neuron.fromChemical.size)

        val iChemicalCapped = iChemicalUncapped max -Neuron.maxChemicalCurrent min Neuron.maxChemicalCurrent
        val iChemical = iChemicalCapped * Synapse.synapticStrength

        // Combine all currents
        val iTotal = iElectrical + iChemical - iLeak

        // Calculate and cap updated state
        val dvDt = iTotal / Neuron.capacitance
        val totalVoltageUncapped = state.voltage + (dvDt * dt)

        val updatedVoltage = totalVoltageUncapped max Neuron.minVoltage min Neuron.maxVoltage
        val updatedLastFired = if updatedVoltage > Neuron.thresholdVoltage then step else state.lastFired
        state.updated(updatedVoltage, updatedLastFired)

      id -> updatedState

  def run(): Unit =
    val start = System.currentTimeMillis()

    val (finalState, end) = (0 to config.totalSteps).foldLeft((initialState, start)):
      case ((currentState, stepStart), step) =>
        val firstTimeThrough = stepStart == start
        def fakeStep = step - Neuron.actionPotentialSteps - 1 // so stimuli won't trigger an action potential
        val stimulatedState = config.stimuli.foldLeft(currentState):
          case (state, (id, stimulus)) =>
            stimulus.getAt(step) match
              case Some(voltage) => state.updatedWith(id)(_.map(_.updated(voltage, fakeStep)))
              case _             => state

        val newState = stepPhysics(step, config.updateState(stimulatedState, step))

        val targetEnd =
          if step % config.refreshEvery != 0 then stepStart // no delay - feed start through to the next step
          else
            visualizer.renderNextFrame(newState, useCachedBuffer = !firstTimeThrough)
            sonifier.voiceNextFrame(step, newState)
            val stepEnd = stepStart + (config.frameDelay max (if firstTimeThrough then 16 else 0))
            val sleep = stepEnd - System.currentTimeMillis()
            val nextStepStart = if sleep > 0 then
              Thread.sleep(sleep)
              stepEnd
            else
              // If we are more than 1 frame behind, reset the clock so the visualizer doesn't aggressively
              // fast-forward to catch up.
              if sleep < -config.frameDelay then System.currentTimeMillis()
              else stepEnd
            nextStepStart

        (newState, targetEnd)

    sonifier.close(config.totalSteps + 1)
    val actualTime = (end - start).toInt
    val simulatedTime = (config.totalSteps * dt).toInt
    val ratio = actualTime.toDouble / simulatedTime
    println(f"\nSimulation complete. Simulated $simulatedTime ms in $actualTime ms (time scale: $ratio%2.2f:1)")
