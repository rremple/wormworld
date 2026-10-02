package wormworld

import intervalidus.DiscreteValue.given
import intervalidus.immutable.DataFunction
import org.neuroml.model.Network
import wormworld.Sonifier.Note.*

import java.io.{File, PrintWriter}
import scala.util.Using

object SimulatedAmphidResponse:
  def main(args: Array[String]): Unit =

    val adjustPianoOctave = -1
    // Listen to the most active 45 neurons
    val notesById = Map(
      // 1. The Sensory Vanguard (The Input - Head)
      "AWCR" -> C(1),
      "AWCL" -> C(1),
      "ASER" -> Eb(1),
      "ASEL" -> Eb(1),
      "ASKR" -> F(1),
      "ASKL" -> F(1),

      // 2. The Integration Hub (The Processors - Nerve Ring)
      "AIAR" -> C(2),
      "AIAL" -> C(2),
      "AIBR" -> Eb(2),
      "AIBL" -> Eb(2),
      "AIZR" -> F(2),
      "AIZL" -> F(2),
      "RIAR" -> G(2),
      "RIAL" -> G(2),
      "AIYR" -> Bb(2),
      "AIYL" -> Bb(2),

      // 3. The Command Core (Grouped by Direction)
      // Backward Drivers (Nerve Ring):
      "AVAR" -> C(3),
      "AVAL" -> C(3),
      "AVDR" -> Eb(3),
      "AVDL" -> Eb(3),
      "AVER" -> F(3),
      "AVEL" -> F(3),
      // Forward Drivers (AVB in Nerve Ring, PVC in Tail):
      "AVBR" -> G(3),
      "AVBL" -> G(3),
      "PVCR" -> Bb(3),
      "PVCL" -> Bb(3),

      // 4. The Modulators (Nerve Ring)
      "RIMR" -> C(4),
      "RIML" -> C(4),
      "AVJR" -> Eb(4),
      "AVJL" -> Eb(4),

      // 5. The Locomotion Engine (Anatomical: Head to Tail)
      "DA1" -> C(5 + adjustPianoOctave),
      "DD1" -> Eb(5 + adjustPianoOctave),
      "VD1" -> F(5 + adjustPianoOctave),
      "DD2" -> G(5 + adjustPianoOctave),
      "VD2" -> Bb(5 + adjustPianoOctave),
      "VD3" -> C(6 + adjustPianoOctave),
      "DD4" -> Eb(6 + adjustPianoOctave),
      "VD4" -> F(6 + adjustPianoOctave),
      "VD5" -> G(6 + adjustPianoOctave),
      "DA6" -> Bb(6 + adjustPianoOctave),
      "VD6" -> C(7 + adjustPianoOctave),
      "VD7" -> Eb(7 + adjustPianoOctave),
      "VA8" -> F(7 + adjustPianoOctave),
      "AS9" -> G(7 + adjustPianoOctave),
      "VA9" -> Bb(7 + adjustPianoOctave)
    )

    // Special chemical edges to simulate the "tonic drive" of current into the gut pacemakers
    val tonicWeight = 0.538 // between 0.535 and 0.54
    val tonicDrive = Seq(
      Synapse.chemical("Tonic_Drive", "MCR", tonicWeight),
      Synapse.chemical("Tonic_Drive", "MCL", tonicWeight)
    )

    val network: Network = ConnectomeLoader.loadNetwork
    val chemicalEdges: Seq[Synapse] = tonicDrive ++ ConnectomeLoader.chemicalEdges(network)
    val electricalEdges: Seq[Synapse] = ConnectomeLoader.electricalEdges(network)

    val amphidStimulus = DataFunction(
      Simulator.triangleWave(
        startStep = 0,
        peakVoltage = -25.0,
        duration = (50 / Simulator.dt).toInt // ms to steps
      )
    )

    // Virtual organism gets confused by a ghost scent and decides to back up.
    // Stimulating left and right amphid sensory neurons connecting into the nerve ring
    val amphidStimuli: Map[String, DataFunction.In1D[Double, Int]] = Map(
      "ASIL" -> amphidStimulus,
      "ASIR" -> amphidStimulus,
      "Tonic_Drive" -> DataFunction(Simulator.squareWave(0, Neuron.maxVoltage))
    )

//    val neuronsToLog = (amphidStimuli.keySet ++ notesById.keySet).toList.sorted

    val logEvery = 100
    Using(new PrintWriter(new File("amphid-response.csv"))): csv =>
      def logState(currentState: Map[String, Neuron.State], step: Int): Map[String, Neuron.State] =
//        if step == 0 then csv.println(neuronsToLog.mkString("step,", ",", ""))
//        csv.println(neuronsToLog.map(currentState(_).voltage).mkString(s"$step,", ",", ""))
        if step % logEvery == 0 then println(s"$step...")
        currentState

      val simulator = Simulator(
        chemicalEdges,
        electricalEdges,
        Simulator.Config(
          simulationName = "Amphid Response",
          stimuli = amphidStimuli,
          updateState = logState,
          notesById = notesById,
          refreshEvery = 1,
          totalSteps = 7000,
          frameDelay = 50 // ms
        )
      )

      simulator.run() // Fire it up!
    .recover(e => println(s"File error: $e"))
