package wormworld

import java.io.{File, PrintWriter}
import scala.util.Using

object DrawIoExporter:

  /**
    * Exports a diagram to draw.io format
    * @param simulationName
    *   name of the simulation
    * @param filePrefix
    *   lowercase file prefix
    * @param placementById
    *   neuron information including placement coordinates
    */
  def exportToDrawIo(
    simulationName: String,
    filePrefix: String,
    placementById: Map[String, Neuron.Placed]
  ): Unit =
    val filename = s"$filePrefix.drawio" // .drawio extension opens natively!
    val neurons = placementById.values.map(_.neuron)
    // Calculate global max weights for smooth 0-100 opacity scaling
    val chemicalWeights = neurons.flatMap(n => (n.fromChemical ++ n.toChemical).map(_.weight.abs))
    val electricalWeights = neurons.flatMap(_.electrical.map(_.weight.abs))

    val chemicalWeightMax = chemicalWeights.maxOption.filter(_ > 0).getOrElse(0.001)
    val electricalWeightMax = electricalWeights.maxOption.filter(_ > 0).getOrElse(0.001)

    // Helper to map weight strictly to an opacity percentage (10 to 100)
    def getOpacity(weight: Double, max: Double): Int =
      ((weight.abs / max) * 100).round.toInt max 10 min 100

    Using(new PrintWriter(new File(filename))): out =>
      out.println("""<?xml version="1.0" encoding="UTF-8"?>""")
      out.println("""<mxfile host="Electron">""")
      out.println(s"""  <diagram name="$simulationName" id="${filePrefix}_diagram">""")

      // Expanded the page width/height so the nodes have plenty of room to breathe
      out.println(
        """    <mxGraphModel dx="1000" dy="1000" grid="1" gridSize="10" guides="1" tooltips="1"""" +
          """ connect="1" arrows="1" fold="1" page="1" pageScale="1" pageWidth="2000" pageHeight="1600"""" +
          """ math="0" shadow="0">"""
      )
      out.println("""      <root>""")
      out.println("""        <mxCell id="0" />""")
      out.println("""        <mxCell id="1" parent="0" />""")

      // Canvas bounds to stretch the 0.0 - 1.0 layout coordinates across
      val canvasWidth = 1600.0
      val canvasHeight = 1200.0

      // ==========================================
      // 1. Draw Nodes
      // ==========================================
      placementById.foreach: (id, p) =>
        val n = p.neuron
        val outlineColor =
          if n.fromChemical.isEmpty then "#FF0000"
          else if n.toChemical.isEmpty then "#0000FF"
          else "#000000"

        // Map 0.0-1.0 placement variables to actual pixel coordinates, padded by 100px
        val x = (p.x * canvasWidth).round.toInt + 100
        val y = (p.y * canvasHeight).round.toInt + 100

        out.println(
          "        " +
            s"""<mxCell id="n_$id" parent="1" style="ellipse;whiteSpace=wrap;html=1;aspect=fixed;""" +
            s"""strokeColor=$outlineColor;strokeWidth=2;" value="$id" vertex="1">"""
        )
        out.println(s"""          <mxGeometry height="40" width="40" x="$x" y="$y" as="geometry" />""")
        out.println(s"""        </mxCell>""")

      // ==========================================
      // 2. Draw Edges
      // ==========================================
      neurons.foreach: n =>
        val id = n.id
        // Chemical Synapses (Red, Directed) -- avoid doubling these by only drawing the "to" side
        n.toChemical.foreach: c =>
          val opacity = s"opacity=${getOpacity(c.weight, chemicalWeightMax)}"
          val style = s"""style="html=1;curved=1;strokeColor=#FF0000;$opacity;""""
          out.println(
            s"""        <mxCell id="e_chem_${id}_${c.id}" edge="1" parent="1" """ +
              s"""source="n_$id" target="n_${c.id}" $style>"""
          )
          out.println("""          <mxGeometry relative="1" as="geometry" />""")
          out.println("""        </mxCell>""")

        // Gap Junctions (Blue, Undirected) -- avoid doubling these by only drawing when c.id >= n.id
        n.electrical
          .filter(_.id >= n.id)
          .foreach: c =>
            val opacity = getOpacity(c.weight, electricalWeightMax)
            val style = s"html=1;curved=1;strokeColor=#0000FF;endArrow=none;endFill=0;$opacity;"
            out.println(
              "        " +
                s"""<mxCell id="e_elec_${id}_${c.id}" edge="1" parent="1" source="n_$id" target="n_${c.id}" $style>"""
            )
            out.println("""          <mxGeometry relative="1" as="geometry" />""")
            out.println("""        </mxCell>""")

      out.println("""      </root>""")
      out.println("""    </mxGraphModel>""")
      out.println("""  </diagram>""")
      out.println("""</mxfile>""")
    .recover(e => println(s"File error: $e"))
