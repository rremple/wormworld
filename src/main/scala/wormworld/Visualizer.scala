package wormworld

import java.awt.*
import java.awt.event.{ComponentAdapter, ComponentEvent, MouseEvent}
import java.awt.geom.{Ellipse2D, Line2D, Point2D, QuadCurve2D}
import java.awt.image.BufferedImage
import javax.swing.{JFrame, JPanel, ToolTipManager, WindowConstants}

import scala.collection.mutable
import scala.language.implicitConversions

object Visualizer:
  def apply(config: Simulator.Config, filePrefix: String, neurons: Seq[Neuron]): Visualizer =
    val electricalWeights = neurons.flatMap(_.electrical.map(_.weight.abs))
    val chemicalWeights = neurons.flatMap(n => (n.fromChemical ++ n.toChemical).map(_.weight.abs))

    val rankBuilder = Map.newBuilder[String, Int]
    neurons
      .filter(_.fromChemical.isEmpty)
      .foreach: n =>
        rankBuilder.addOne(n.id, 0)

    neurons
      .filter(_.toChemical.isEmpty)
      .foreach: n =>
        rankBuilder.addOne(n.id, neurons.size + 1)
    val ranked = (1 to neurons.size).foldLeft(rankBuilder.result().keySet): (prior, rank) =>
      if prior.size == neurons.size then prior
      else
        val unrankedWithInDegree =
          neurons.filterNot(n => prior.contains(n.id)).map(n => n -> (n.fromChemical.map(_.id).toSet -- prior).size)
        val minInDegree = unrankedWithInDegree.map(_._2).min // might be zero
        val thisRank = unrankedWithInDegree.filter(_._2 == minInDegree).map(_._1.id)
        rankBuilder.addAll(thisRank.map(_ -> rank))
        prior ++ thisRank
    require(ranked.size == neurons.size, s"Sadly ${neurons.size - ranked.size} remain unranked...")
    val rankedByInts = rankBuilder.result()
    val maxNonSinkRank = rankedByInts.values.filterNot(_ == neurons.size + 1).max
    val rankedByDoubles = rankedByInts.map: (k, v) =>
      k -> (if v == neurons.size + 1 then 1.0 else v.toDouble / (maxNonSinkRank + 1))

    val electricalWeightsMax = electricalWeights.maxOption.getOrElse(0.0)
    val electricalWeightsMin = electricalWeights.minOption.getOrElse(0.0) min (electricalWeightsMax - 0.1)
    val chemicalWeightsMax = chemicalWeights.maxOption.getOrElse(0.0)
    val chemicalWeightsMin = chemicalWeights.minOption.getOrElse(0.0) min (chemicalWeightsMax - 0.1)

    val start = Map.from(neurons.map(n => n.id -> Neuron.Placed(n, rankedByDoubles(n.id))))
    val mainPanel = new Visualizer(
      config,
      filePrefix,
      mutable.Map.from(start),
      1200,
      675,
      electricalWeightsMin,
      electricalWeightsMax,
      chemicalWeightsMin,
      chemicalWeightsMax
    )
    val frame = JFrame(s"Visualize ${config.simulationName}")
    frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE)
    frame.getContentPane.add(mainPanel)
    frame.pack()
    frame.setLocationByPlatform(true)
    frame.setVisible(true)
    mainPanel

import wormworld.Visualizer.*

class Visualizer(
  config: Simulator.Config,
  filePrefix: String,
  placementById: mutable.Map[String, Neuron.Placed],
  preferredWidth: Int,
  preferredHeight: Int,
  electricalWeightsMin: Double,
  electricalWeightsMax: Double,
  chemicalWeightsMin: Double,
  chemicalWeightsMax: Double
) extends JPanel:
  require(placementById.size > 1, s"${config.simulationName} must have have at least a small brain")

  ToolTipManager.sharedInstance().registerComponent(this)

  val initState = placementById.map((id, p) => id -> Neuron.State.initial(p.neuron)).toMap
  private var currentState = initState

  private var currentBuffer: BufferedImage =
    new BufferedImage(preferredWidth, preferredHeight, BufferedImage.TYPE_INT_ARGB_PRE)

  // The simulator calls this to draw a new frame
  def renderNextFrame(newState: Map[String, Neuron.State], useCachedBuffer: Boolean): Unit =
    val currentWidth = getWidth
    val currentHeight = getHeight
    currentState = newState // stateful, so we don't lose these on a resize

    // Swing can sometimes call this before the window is fully realized on screen
    if currentWidth > 0 && currentHeight > 0 then

      // Recreate the buffer ONLY if the window was resized
      val sameSize = currentBuffer.getWidth == currentWidth && currentBuffer.getHeight == currentHeight
      if !sameSize then currentBuffer = new BufferedImage(currentWidth, currentHeight, BufferedImage.TYPE_INT_ARGB)

      val g2d = currentBuffer.createGraphics()

      val reuseBuffer = useCachedBuffer && sameSize
      // If not reusing prior buffer, clear background using the current dimensions
      if !reuseBuffer then
        g2d.setColor(java.awt.Color.WHITE)
        g2d.fillRect(0, 0, currentWidth, currentHeight)

      // Execute custom drawing
      paintComponent2D(g2d, newState, reuseBuffer)
      g2d.dispose()

      // Request the UI thread to paint the finished buffer
      repaint()

  // Blit to the screen
  override protected def paintComponent(g: Graphics): Unit =
    super.paintComponent(g)
    g.drawImage(currentBuffer, 0, 0, null)

  // Listen for window resize events and force a redraw
  this.addComponentListener(
    new ComponentAdapter:
      override def componentResized(e: ComponentEvent): Unit = renderNextFrame(currentState, useCachedBuffer = false)
  )

  private val iterations = 100 // Relaxation iterations
  private val pullStrength = 0.5 // How much to pull in each relaxation iteration
  private val nodeRadius = 0.025 // Minimum Y-distance required between nodes in a column
  private val columns = 16.0 // How many columns to use when pushing nodes apart

  private def relaxY(placedNeurons: mutable.Map[String, Neuron.Placed]): Seq[(String, Neuron.Placed)] =
    // Phase 1: Pure Barycentric Pull (Finds perfect topological order, allows overlap)
    val pulledFrame = placedNeurons.values.map: placed =>
      val neighbors =
        (placed.neuron.fromChemical ++ placed.neuron.toChemical ++ placed.neuron.electrical).map(_.id).toSet

      val pull = if neighbors.nonEmpty then
        val centerY = (placed.y + neighbors.flatMap(placedNeurons.get).map(_.y).sum) / (neighbors.size + 1)
        (centerY - placed.y) * pullStrength
      else 0.0

      placed.withY(placed.y + pull)

    // Phase 2: The Sweep (Un-overlap the nodes without blowing up the graph)
    // Group by X coordinate to establish "columns" (rounding slightly to group nearby nodes)
    val groupedByX = pulledFrame.groupBy(n => (n.x * columns).round / columns)

    val unoverlappedFrame = groupedByX.toSeq.flatMap: (_, columnNodes) =>
      val sortedNodes = columnNodes.toSeq.sortBy(_.y)

      // Nudge nodes up if they are too close to the one below them
      val stacked = sortedNodes.foldLeft(Seq.empty[Neuron.Placed]): (acc, current) =>
        acc.lastOption match
          case None       => Seq(current)
          case Some(prev) =>
            val safeY = current.y max (prev.y + nodeRadius)
            acc :+ current.withY(safeY)

      // Re-center the column so the nudging doesn't just push everything to the floor
      val originalCenter = if sortedNodes.nonEmpty then sortedNodes.map(_.y).sum / sortedNodes.size else 0.5
      val newCenter = if stacked.nonEmpty then stacked.map(_.y).sum / stacked.size else 0.5
      val centerShift = originalCenter - newCenter

      // Apply center shift and strictly clamp to the [0.0, 1.0] viewport
      stacked.map: placed =>
        val finalY = (placed.y + centerShift).max(0.0).min(1.0)
        placed.id -> placed.withY(finalY)

    unoverlappedFrame

  def relaxAll(): Unit =
    (1 to iterations).foreach: _ =>
      placementById ++= relaxY(placementById)

  // do a final spread on the refined layout
  def spreadAll(): Unit =
    val yValues = placementById.values.map(_.y)
    val (minY, maxY) = (yValues.min, yValues.max)
    val rangeY = maxY - minY
    placementById.values.foreach: p =>
      placementById.update(p.id, p.withY((p.y - minY) / rangeY))
    // Once the format is final, we can export to drawio (if needed)
    DrawIoExporter.exportToDrawIo(config.simulationName, filePrefix, placementById.toMap)

  override def getPreferredSize: Dimension = Dimension(preferredWidth, preferredHeight)

  val margin: Double = 10.0 // top/bottom/left/right margin (nothing is drawn here)

  // will be updated with actual width/height/neuron size
  private var insideDomain: Double = preferredWidth.toDouble - margin * 2.0
  private var insideRange: Double = preferredHeight.toDouble - margin * 2.0
  private var neuronSize: Double = insideRange / 60 // size of open circles for neurons

  def domainPosition(value: Double): Double =
    require(value <= 1.0 && value >= 0.0, s"0.0 <= $value <= 1.0")
    margin + value * insideDomain

  def rangePosition(value: Double): Double =
    require(value <= 1.0 && value >= 0.0, s"0.0 <= $value <= 1.0")
    margin + (1.0 - value) * insideRange

  override def getToolTipText(e: MouseEvent): String =
    val mouseX = e.getX
    val mouseY = e.getY
    val hoveredNeuron = placementById.find: (_, n) =>
      val (nx, ny) = (domainPosition(n.x).round.toInt, rangePosition(n.y).round.toInt)
      math.hypot(nx - mouseX, ny - mouseY) <= neuronSize // Euclidean distance
    // the ID string and stats, or null if hovering over empty space (hides the tooltip)
    def format(connections: Seq[Connection]): String =
      if connections.isEmpty then "none" else connections.mkString(", ")
    hoveredNeuron
      .map: (id, p) =>
        f"""<html><b>$id</b><br>
           |Voltage: ${currentState(id).voltage}%2.2f mV<br>
           |Chemical in: ${format(p.neuron.fromChemical)}<br>
           |Chemical out: ${format(p.neuron.toChemical)}<br>
           |Electrical: ${format(p.neuron.electrical)}
           |</html>""".stripMargin
      .orNull

  private def paintComponent2D(
    graphics: Graphics2D,
    state: Map[String, Neuron.State],
    usingCachedBuffer: Boolean
  ): Unit =
    insideDomain = getWidth.toDouble - margin * 2.0
    insideRange = getHeight.toDouble - margin * 2.0
    neuronSize = insideRange / 60

    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_SPEED)

    // val fontMetrics = graphics.getFontMetrics

    val alphaMin = 1
    val alphaMax = 250
    val electricalAlphaRatio = (electricalWeightsMax - electricalWeightsMin) / (alphaMax - alphaMin)
    val chemicalAlphaRatio = (chemicalWeightsMax - chemicalWeightsMin) / (alphaMax - alphaMin)
    def electricalAlpha(weight: Double): Double = (weight.abs - electricalWeightsMin) / electricalAlphaRatio + alphaMin
    def chemicalAlpha(weight: Double): Double = (weight.abs - chemicalWeightsMin) / chemicalAlphaRatio + alphaMin

    def neuronColor(voltage: Double) =
      import Neuron.{centerVoltage, minVoltage, maxVoltage}
      val redRange = maxVoltage - centerVoltage
      val blueRange = centerVoltage - minVoltage

      // Red for excited (above center), Blue for resting/inhibited (below center)
      val (red, blue, range) = if voltage > centerVoltage then (255, 0, redRange) else (0, 255, blueRange)

      // Calculate intensity based on how far we are from the center in either direction
      val intensity = (voltage - centerVoltage).abs / range

      new java.awt.Color(red, 0, blue, (intensity * 255).round.toInt max 0 min 255)

    val solidBlack = new java.awt.Color(0, 0, 0, 255)
    val solidRed = new java.awt.Color(255, 0, 0, 255)
    val solidBlue = new java.awt.Color(0, 0, 255, 255)
    def faintRed(alpha: Double) = new java.awt.Color(255, 0, 0, alpha.round.toInt max 0 min 255)
    def faintBlue(alpha: Double) = new java.awt.Color(0, 0, 255, alpha.round.toInt max 0 min 255)

    def drawNeuron(point: Point2D.Double, isSource: Boolean, isSink: Boolean, voltage: Double): Unit =
      val size = neuronSize
      val halfSize = size / 2
      val ellipse = Ellipse2D.Double(point.x - halfSize, point.y - halfSize, size, size)
      if usingCachedBuffer then
        graphics.setColor(Color.WHITE) // opaque
        graphics.fill(ellipse)
      val outlineColor = if isSource then solidRed else if isSink then solidBlue else solidBlack
      val fillColor = neuronColor(voltage)
      if fillColor.getAlpha != 0 then
        graphics.setColor(fillColor)
        graphics.fill(ellipse)
      graphics.setColor(outlineColor)
      graphics.draw(ellipse)

    def createConnectionShape(fromPoint: Point2D.Double, toPoint: Point2D.Double): Shape =
      val deltaX = toPoint.x - fromPoint.x
      val deltaY = toPoint.y - fromPoint.y
      // If nearly horizontal or vertical relative to each other, use a curved line that bends in from the screen edges
      if deltaX.abs < 5.0 || deltaY.abs < 5.0 then
        val xBow = math.signum(domainPosition(0.5) - fromPoint.x)
        val yBow = math.signum(rangePosition(0.5) - fromPoint.y)
        val ctrlX = if deltaX.abs < 5.0 then fromPoint.x + deltaY.abs * 0.15 * xBow else fromPoint.x + (deltaX / 2.0)
        val ctrlY = if deltaY.abs < 5.0 then fromPoint.y + deltaX.abs * 0.15 * yBow else fromPoint.y + (deltaY / 2.0)
        QuadCurve2D.Double(fromPoint.x, fromPoint.y, ctrlX, ctrlY, toPoint.x, toPoint.y)
      else
        // For standard connections, keep the straight line
        Line2D.Double(fromPoint.x, fromPoint.y, toPoint.x, toPoint.y)

    def drawChemicalConnection(fromPoint: Point2D.Double, toPoint: Point2D.Double, alpha: Double): Unit =
      graphics.setColor(faintRed(alpha))
      graphics.draw(createConnectionShape(fromPoint, toPoint))

    def drawElectricalConnection(fromPoint: Point2D.Double, toPoint: Point2D.Double, alpha: Double): Unit =
      graphics.setColor(faintBlue(alpha))
      graphics.draw(createConnectionShape(fromPoint, toPoint))

    // draw everything

    placementById.values.foreach: p =>
      val pointP = Point2D.Double(domainPosition(p.x), rangePosition(p.y))

      if !usingCachedBuffer then
        p.neuron.toChemical.foreach: c =>
          val q = placementById(c.id)
          val pointQ = Point2D.Double(domainPosition(q.x), rangePosition(q.y))
          drawChemicalConnection(pointP, pointQ, chemicalAlpha(c.weight))

        p.neuron.electrical
          .filter(_.id >= p.id)
          .foreach: c =>
            val q = placementById(c.id)
            val pointQ = Point2D.Double(domainPosition(q.x), rangePosition(q.y))
            drawElectricalConnection(pointP, pointQ, electricalAlpha(c.weight))

      drawNeuron(
        pointP,
        isSource = p.neuron.fromChemical.isEmpty,
        isSink = p.neuron.toChemical.isEmpty,
        state(p.id).voltage // the only thing that changes
      )
