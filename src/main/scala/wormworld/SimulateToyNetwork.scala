package wormworld

import intervalidus.DiscreteValue.given
import intervalidus.immutable.DataFunction

/**
  * This is not the worm. It only tests basic physics mechanics:
  *   - Excitatory Chain (N1 -> N2): Pokes N1, and that causes N2 to charge up. It crosses the visual threshold (turns
  *     blue!), hits the -20.0 mV refractory ceiling, peaks, resets, and goes temporarily deaf.
  *   - The Inhibitory Brake (N2 -> N3, weight = negative): Verifies that when N2 fires, it actively forces N3 below its
  *     -50.0 mV resting state, proving the GABA logic works.
  *   - The Gap Junction (N1 <-> N3): The electrical connection between the start and end of the chain causes voltage to
  *     physically diffuses backward without seizing the organism.
  */
object SimulateToyNetwork:
  def main(args: Array[String]): Unit =
    println("--- Toy 3 Neuron Connectome ---")

    // Chemical Synapses (Unidirectional)
    val chemicalEdges: Seq[Synapse] = Seq(
      Synapse.chemical(from = "N1", to = "N2", weight = 1.0),
      Synapse.chemical(from = "N2", to = "N3", weight = -0.1)
    )

    // Gap Junctions (Bidirectional)
    val electricalEdges: Seq[Synapse] = Seq(
      Synapse.electrical(cellA = "N3", cellB = "N1", weight = 1.0)
    )

    val stimuli: Map[String, DataFunction.In1D[Double, Int]] = Map(
      "N1" -> DataFunction(
        Simulator.squareWave(
          startStep = 50, // poke starting at this entry after start
          voltage = Neuron.maxVoltage,
          duration = Some((200 / Simulator.dt).toInt) // 200 ms in steps = 2000
        )
      )
    )

    // just log to the terminal
    def logState(currentState: Map[String, Neuron.State], step: Int): Map[String, Neuron.State] =
      if step == 0 then println("step\tn1\tn2\tn3\t")
      val n1State = currentState("N1")
      val n2State = currentState("N2")
      val n3State = currentState("N3")
      println(s"$step\t${n1State.voltage}\t${n2State.voltage}\t${n3State.voltage}")
      currentState

    val simulator = Simulator(
      chemicalEdges,
      electricalEdges,
      Simulator.Config(
        "Toy Network",
        stimuli,
        logState,
        refreshEvery = 1,
        totalSteps = 8000,
        frameDelay = 32 // ms
      )
    )

    // Fire it up!
    simulator.run()
