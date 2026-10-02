package wormworld

import intervalidus.DiscreteValue.given
import intervalidus.immutable.DataFunction
import org.neuroml.model.Network
import wormworld.Sonifier.Note.*

import java.io.{File, PrintWriter}
import scala.util.Using

object SimulatedGutReaction:
  def main(args: Array[String]): Unit =

    // Listen to gut neurons only
    val notesById = Map(
      // Pacemakers (Bass) +3
      "MCL" -> C(2), // spikes 4th
      "MCR" -> C(2), // spikes 4th

      // Motor (Mids)
      "M2L" -> C(3),
      "M2R" -> C(3),
      "M3L" -> Eb(3),
      "M3R" -> Eb(3),
      "M1" -> F(3), // spikes 2nd
      "M4" -> G(3),
      "M5" -> Bb(3),

      // Interneurons (High)
      "I1L" -> C(4), // spikes 1st
      "I1R" -> C(4), // spikes 1st
      "I2L" -> Eb(4),
      "I2R" -> Eb(4),
      "I4" -> F(4),
      "I5" -> G(4),
      "I6" -> Bb(4),
      "I3" -> C(5), // spikes 3rd
      "MI" -> Eb(5),

      // Neurosecretory Motor Neurons
      "NSML" -> C(6),
      "NSMR" -> C(6),
      "RIPL" -> Eb(6),
      "RIPR" -> Eb(6)
    )

    // Special chemical edges to simulate the "tonic drive" of current into the gut pacemakers
    val tonicWeight = 0.538 // between 0.535 and 0.54
    val tonicDrive = Seq(
      Synapse.chemical("Tonic_Drive", "MCR", tonicWeight),
      Synapse.chemical("Tonic_Drive", "MCL", tonicWeight)
    )

    // Filter for gut neurons only
    val gutNeurons = notesById.keySet
    def gutSynapse(s: Synapse): Boolean = gutNeurons.contains(s.from) && gutNeurons.contains(s.to)

    val net: Network = ConnectomeLoader.loadNetwork
    val chemicalEdges: Seq[Synapse] = tonicDrive ++ ConnectomeLoader.chemicalEdges(net).filter(gutSynapse)
    val electricalEdges: Seq[Synapse] = ConnectomeLoader.electricalEdges(net).filter(gutSynapse)

    val pharynxDuration = Some((15 / Simulator.dt).toInt) // ms to steps
    val pharynxStimulus: DataFunction.In1D[Double, Int] = DataFunction(
      Simulator.squareWave(800, Neuron.maxVoltage, pharynxDuration) ++
        Simulator.squareWave(1800, Neuron.maxVoltage, pharynxDuration) ++
        Simulator.squareWave(2800, Neuron.maxVoltage, pharynxDuration) ++
        Simulator.squareWave(3800, Neuron.maxVoltage, pharynxDuration)
    )

    // Pharynx senses food present starting a little ways in
    val stimuli: Map[String, DataFunction.In1D[Double, Int]] = Map(
      // pharynx sensory neurons connecting into gut nerve ring
      "NSML" -> pharynxStimulus,
      "NSMR" -> pharynxStimulus,
      // Tonic_Drive starting shortly after start
      "Tonic_Drive" -> DataFunction(Simulator.squareWave(5, Neuron.maxVoltage)) // always on
    )

    val neuronsToLog = "Tonic_Drive" :: gutNeurons.toList.sorted

    val logEvery = 100
    Using(new PrintWriter(new File("gut-reaction.csv"))): csv =>
      def logState(currentState: Map[String, Neuron.State], step: Int): Map[String, Neuron.State] =
        if step == 0 then csv.println(neuronsToLog.mkString("step,", ",", ""))
        csv.println(neuronsToLog.map(currentState(_).voltage).mkString(s"$step,", ",", ""))
        if step % logEvery == 0 then println(s"step $step")
        currentState

      val simulator = Simulator(
        chemicalEdges,
        electricalEdges,
        Simulator.Config(
          "Gut Reaction",
          stimuli,
          logState,
          notesById,
          refreshEvery = 1,
          totalSteps = 8000,
          frameDelay = 12 // ms
        )
      )

      simulator.run() // Fire it up!
    .recover(e => println(s"File error: $e"))
