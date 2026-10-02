package wormworld

import intervalidus.DiscreteValue.given
import intervalidus.immutable.DataFunction
import org.neuroml.model.Network
import wormworld.Sonifier.Note.*

import java.io.{File, PrintWriter}
import scala.util.Using

object SimulatedTailPoke:
  def main(args: Array[String]): Unit =
    
    val adjustPianoOctave = -1
    // Listen to the most active 45 neurons
    val notesById = Map(
      // The Command Interneuron Tug-of-War
      "LUAL" -> C(1), // Posterior relay from the sensors
      "LUAR" -> C(1),
      "PVCL" -> Eb(1), // Primary forward drive
      "PVCR" -> Eb(1),
      "AVBL" -> F(1), // Sustained forward drive
      "AVBR" -> F(1),
      "AVAL" -> G(1), // Reverse drive (Inhibited)
      "AVAR" -> G(1),
      "AVDL" -> Bb(1), // Reverse drive (Inhibited)
      "AVDR" -> Bb(1),

      // Sensory Ignition & Modulators
      "PLML" -> C(2), // Primary tail stimulus
      "PLMR" -> C(2),
      "PDEL" -> Eb(2), // Secondary stimulus & DVA igniter
      "PDER" -> Eb(2),
      "AVKL" -> F(2), // The asymmetric rogue modulator
      "AVKR" -> F(2),
      "AVJL" -> G(2), // Global state integrator
      "AVJR" -> G(2),
      "BDUL" -> Bb(2), // Touch and locomotion modulator
      "BDUR" -> Bb(2),

      // The Physical Whip & Execution
      "PVNL" -> C(3), // Tail/Lumbar shockwave
      "PVNR" -> C(3),
      "PVWL" -> Eb(3), // Tail/Lumbar response
      "PVWR" -> Eb(3),
      "SABVL" -> F(3), // Head/neck whip and steering
      "SABVR" -> F(3),
      "SIBVL" -> G(3), // Head/neck whip and steering
      "SIBVR" -> G(3),
      "HSNL" -> Bb(3), // The egg-laying survival reflex
      "HSNR" -> Bb(3),

      // 5. The Locomotion Engine (Anatomical: Head to Tail)
      // The Driving Beat (Forward Motor Neurons)
      "DB2" -> C(4 + adjustPianoOctave),
      "DB3" -> Eb(4 + adjustPianoOctave),
      "DB4" -> F(4 + adjustPianoOctave),
      "DB5" -> G(4 + adjustPianoOctave),
      "DB6" -> Bb(4 + adjustPianoOctave),

      // The Syncopation (Cross-Inhibitors)
      "VD2" -> C(5 + adjustPianoOctave),
      "VD3" -> Eb(5 + adjustPianoOctave),
      "VD4" -> F(5 + adjustPianoOctave),
      "VD5" -> G(5 + adjustPianoOctave),
      "VD6" -> Bb(5 + adjustPianoOctave),

      // The Ghost Notes (Silenced Reversers)
      "VA4" -> C(6 + adjustPianoOctave),
      "VA6" -> Eb(6 + adjustPianoOctave),
      "VA9" -> F(6 + adjustPianoOctave),

      // The Accents (Sensors)
      "PVM" -> G(6 + adjustPianoOctave),
      "DVA" -> Bb(6 + adjustPianoOctave)
    )
    println(s"notesById size = ${notesById.size}")

    // Special chemical edges to simulate the "tonic drive" of current into the gut pacemakers
    val tonicWeight = 0.538 // between 0.535 and 0.54
    val tonicDrive = Seq(
      Synapse.chemical("Tonic_Drive", "MCR", tonicWeight),
      Synapse.chemical("Tonic_Drive", "MCL", tonicWeight)
    )

    val network: Network = ConnectomeLoader.loadNetwork
    val chemicalEdges: Seq[Synapse] = tonicDrive ++ ConnectomeLoader.chemicalEdges(network)
    val electricalEdges: Seq[Synapse] = ConnectomeLoader.electricalEdges(network)

    val stimulusStart = 100
    val stimulusCount = 10
    val stimulusDuration = 500
    val stimulusFrac = 1.0
    val stimulusStartVoltage = -10.0
    val stimulusSpread = stimulusCount.toDouble / stimulusFrac

    val touchStimulusLeft = DataFunction(
      (0 until stimulusCount).flatMap: i =>
        Simulator.triangleWave(
          startStep = stimulusStart + i * stimulusDuration,
          peakVoltage = stimulusStartVoltage - (i.toDouble / stimulusFrac),
          duration = stimulusDuration
        )
    )

    val touchStimulusRight = DataFunction(
      (0 until stimulusCount).flatMap: i =>
        Simulator.triangleWave(
          startStep = stimulusStart + i * stimulusDuration,
          peakVoltage = stimulusStartVoltage - stimulusSpread + (i.toDouble / stimulusFrac),
          duration = stimulusDuration
        )
    )

    // Virtual organism gets poked and bolt.
    // Stimulating left and right posterior touch receptor neurons located out in the worm's tail
    val touchStimuli: Map[String, DataFunction.In1D[Double, Int]] = Map(
      "PLML" -> touchStimulusLeft,
      "PLMR" -> touchStimulusRight,
      "Tonic_Drive" -> DataFunction(Simulator.squareWave(0, Neuron.maxVoltage))
    )

//     // Derive neurons from chemical and electrical synapses
//    val allSynapses = chemicalEdges ++ electricalEdges
//    val allNeurons = (allSynapses.map(_.from) ++ allSynapses.map(_.to)).distinct
//    allNeurons.foreach(println)
    val neuronsToLog = notesById.keys.toList.sorted

    val logEvery = 100
    Using(new PrintWriter(new File("tail-poke.csv"))): csv =>
      def logState(currentState: Map[String, Neuron.State], step: Int): Map[String, Neuron.State] =
        if step == 0 then csv.println(neuronsToLog.mkString("step,", ",", ""))
        csv.println(neuronsToLog.map(currentState(_).voltage).mkString(s"$step,", ",", ""))
        if step % logEvery == 0 then println(s"$step...")
        currentState

      val simulator = Simulator(
        chemicalEdges,
        electricalEdges,
        Simulator.Config(
          simulationName = "Tail Poke",
          stimuli = touchStimuli,
          updateState = logState,
          notesById = notesById,
          centerThreshold = 50, // Makes the piano softer and sustains the DVA and PVM notes a bit longer
          centerLeftPrefix = Some("DB"),
          centerRightPrefix = Some("VD"),
          totalSteps = stimulusStart + (stimulusCount + 1) * stimulusDuration,
          refreshEvery = 1,
          frameDelay = 10 // ms
        )
      )

      simulator.run() // Fire it up!
    .recover(e => println(s"File error: $e"))
