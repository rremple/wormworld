package wormworld

import org.neuroml.model.{Network, NeuroMLDocument}
import org.neuroml.model.util.NeuroMLConverter

import java.net.URI
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/**
  * Load the canonical C. elegans 302-neuron network generated in the openworm/c302 project.
  *
  * Using the middle-ground conductance-based model (i.e., the one with the "C" in the name). In this model, neurons
  * feature specific ion channels and leakages to generate realistic, continuous action potentials and graded synaptic
  * transmissions.
  */
object ConnectomeLoader:

  private val network = "c302_C_Full.net.nml"
  private val ConnectomeUrl = s"https://raw.githubusercontent.com/openworm/c302/refs/heads/master/examples/" + network
  private val ConnectomeLocalFile = """..\c302\examples\""" + network // if you pull the repo locally

  def loadModelFromLocalFile: Try[NeuroMLDocument] =
    Using(scala.io.Source.fromFile(ConnectomeLocalFile)): source =>
      val rawXml = source.mkString
      NeuroMLConverter().loadNeuroML(rawXml)

  def loadModelFromUri: Try[NeuroMLDocument] =
    println("FYI: attempting to load from an openworm internet source")
    Using(URI.create(ConnectomeUrl).toURL.openStream()): stream =>
      val rawXml = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      NeuroMLConverter().loadNeuroML(rawXml)

  // try local file first, then use the internet if it can't be found
  def loadModel: Try[NeuroMLDocument] = loadModelFromUri.recoverWith(_ => loadModelFromUri)

  // Get the first (and presumably only) network in the model. Throws exception if unsuccessful
  def loadNetwork: Network =
    println("--- Fetching C. elegans Connectome ---")

    val model: NeuroMLDocument = ConnectomeLoader.loadModel.fold(
      err => throw Exception(s"Failed to load connectome", err),
      identity
    )

    val net: Network = model.getNetwork.asScala.toList.head
    println(s"Successfully loaded NeuroML network ${net.getId}")
    net

  // Helper to strip NeuroML hierarchical paths (e.g. "../ADAL/0/GenericNeuronCell" -> "ASER")
  def extractId(rawPath: String): String = rawPath.split("/")(1)

  // Extract Chemical Synapses (Unidirectional)
  def chemicalEdges(net: Network): Seq[Synapse] = net.getProjection.asScala
    .flatMap: proj =>
      val synapseType = proj.getSynapse

      // Inhibitory if the synapse template contains "inh" (weight will be flipped to negative)
      val isInhibitory = synapseType != null && synapseType.contains("inh")
      proj.getConnectionWD.asScala.map: conn =>
        Synapse.chemical(
          from = extractId(conn.getPreCellId),
          to = extractId(conn.getPostCellId),
          weight = (if isInhibitory then -conn.getWeight else conn.getWeight) * Neuron.chemicalConductance
        )
    .toSeq

  // Extract Gap Junctions (Bidirectional)
  def electricalEdges(net: Network): Seq[Synapse] = net.getElectricalProjection.asScala
    .flatMap(_.getElectricalConnectionInstanceW.asScala)
    .map: gap =>
      // Model ohmic gap junctions as two directed edges for network flow analysis
      Synapse.electrical(
        cellA = extractId(gap.getPreCell),
        cellB = extractId(gap.getPostCell),
        weight = gap.getWeight * Neuron.gapJunctionConductance
      )
    .toSeq

@main
def tryIt(): Unit =
  val model: NeuroMLDocument = ConnectomeLoader.loadModel.fold(
    err => throw Exception(s"Failed to load connectome", err),
    identity
  )

  val net: Network = model.getNetwork.asScala.toList.head
  println(s"Successfully loaded NeuroML network ${net.getId}")

  val chemicalEdges: Seq[Synapse] = ConnectomeLoader.chemicalEdges(net)

  val electricalEdges: Seq[Synapse] = ConnectomeLoader.electricalEdges(net)

  val id = "VC6"
  println(s"chemical to   $id:   ${chemicalEdges.filter(_.to == id).map(_.from).mkString(",")}")
  println(s"chemical fm   $id:   ${chemicalEdges.filter(_.from == id).map(_.to).mkString(",")}")
  println(s"electrical to $id: ${electricalEdges.filter(_.to == id).map(_.from).mkString(",")}")
  println(s"electrical fm $id: ${electricalEdges.filter(_.from == id).map(_.to).mkString(",")}")


