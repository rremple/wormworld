package wormworld

import javax.sound.midi.*
import javax.sound.midi.ShortMessage.{CONTROL_CHANGE, NOTE_OFF, NOTE_ON, PROGRAM_CHANGE}

import scala.collection.mutable

object Sonifier:
  // using Pentatonic Scale (e.g., C Minor Pentatonic: C, Eb, F, G, Bb). Because the pentatonic scale lacks half-steps,
  // no matter how many neurons fire simultaneously, it will never sound dissonant or muddy. (like wind chimes)
  enum Note(value: Int):
    case C extends Note(12)
    case Eb extends Note(15)
    case F extends Note(17)
    case G extends Note(19)
    case Bb extends Note(22)

    // add/subtract 12 for octive up/down
    def apply(octave: Int): Int = value + octave * 12

  object Note:
    def noteString(i: Int): String =
      val o = i / 12 - 1
      val n = i % 12 + 12 match
        case 12 => "C"
        case 15 => "Eb"
        case 17 => "F"
        case 19 => "G"
        case 22 => "Bb"
        case _  => "?"
      s"$n($o)"

  extension (i: Int) def noteString: String = Note.noteString(i)

class Sonifier(config: Simulator.Config, filePrefix: String):
  val receiver: Receiver = MidiSystem.getReceiver
  val stats = mutable.Map.empty[String, Int].withDefaultValue(0)
  def noteOnStat(note: Int) =
    val s = Sonifier.Note.noteString(note)
    stats.update(s, stats(s)+1)

  def isLeft(id: String): Boolean = id.endsWith("L")
  def isRight(id: String): Boolean = id.endsWith("R")
  def isCenterLeft(id: String): Boolean = config.centerLeftPrefix.exists(id.startsWith)
  def isCenterRight(id: String): Boolean = config.centerRightPrefix.exists(id.startsWith)

  val centerBalance = 64
  val centerWideOffset = 32 // 0 (balanced) to 63 (wide)
  val centerLeft = centerBalance - centerWideOffset
  val centerRight = centerBalance + centerWideOffset

  // Out is now strictly for writing to the exported MIDI sequence
  class Out(id: String, sequence: Sequence, fileChannel: Int, val volumeThresholdOn: Int):
    val track: Track = sequence.createTrack()

    // Inject the neuron's ID as the Track Name MetaMessage (0x03) at tick 0
    private val nameBytes = id.getBytes("UTF-8")
    private val metaMsg = MetaMessage()
    metaMsg.setMessage(0x03, nameBytes, nameBytes.length)
    track.add(MidiEvent(metaMsg, 0))

    private def addToFile(tick: Long, command: Int, data1: Int, data2: Int): Out =
      track.add(MidiEvent(ShortMessage(command, fileChannel, data1, data2), tick))
      this

    def setInstrument(tick: Long, instrumentId: Int): Out = addToFile(tick, PROGRAM_CHANGE, instrumentId, 0)

    def setBalance(tick: Long, balance: Int): Out = addToFile(tick, CONTROL_CHANGE, 10, balance)

    def noteOn(tick: Long, note: Int): Out =
      noteOnStat(note)
      addToFile(tick, NOTE_ON, note, 100)

    def noteOff(tick: Long, note: Int): Out = addToFile(tick, NOTE_OFF, note, 0)

    def setVolume(tick: Long, volume: Int): Out = addToFile(tick, CONTROL_CHANGE, 11, volume)

  object Live:
    def setInstrument(channel: Int, instrument: Int): Unit =
      receiver.send(ShortMessage(PROGRAM_CHANGE, channel, instrument, 0), -1)

    def setBalance(channel: Int, balance: Int): Unit =
      receiver.send(ShortMessage(CONTROL_CHANGE, channel, 10, balance), -1)

    def noteOn(channel: Int, note: Int): Unit =
      receiver.send(ShortMessage(NOTE_ON, channel, note, 100), -1)

    def noteOff(channel: Int, note: Int): Unit =
      receiver.send(ShortMessage(NOTE_OFF, channel, note, 0), -1)

    def setVolume(channel: Int, volume: Int): Unit =
      receiver.send(ShortMessage(CONTROL_CHANGE, channel, 11, volume), -1)

  private object Out:
    private val instrumentLead = 80 //  Lead 1 (Square Wave) - Pure, harsh, great for testing exact thresholds
    private val instrumentStrings = 48 // String Ensemble 1 - Smooth, cinematic, great for testing gradual swells
    private val instrumentPad = 89 // Pad 2 (Warm) - Thick analog synth pad, excellent for chords

    // Melodic Percussion options for the Nerve Ring
    private val instrumentVibraphone = 11
    private val instrumentMarimba = 12
    private val instrumentCelesta = 8
    private val instrumentMusicBox = 10
    private val instrumentPiano = 0
    private val instrumentPianoRhodes = 4
    private val instrumentPianoDX7 = 5

    val instrumentCentered: Int = instrumentPiano
    val instrumentSeparated: Int = instrumentStrings

  class Stem(name: String, instrumentId: Int, defaultBalance: Int, volumeThreshold: Int):
    // Slow down sound exactly 500x:
    // 1,000 ms/seconds / 0.1 ms/tick = 10,000 ticks/second
    // 10 ticks per quarter note * 2 quarter notes per second (120 bpm) = 20 ticks/second
    // 10,000 / 20 = 500
    val sequence: Sequence = Sequence(Sequence.PPQ, 10)
    private var nextFileChannel = 0

    def allocateOut(id: String): Out =
      if nextFileChannel == 9 then nextFileChannel += 1 // Skip drums!
      if nextFileChannel > 15 then throw Exception(s"16-channel limit exceeded on stem: $name")

      val out = Out(id, sequence, nextFileChannel, volumeThreshold)
      out.setInstrument(0, instrumentId)
      if isCenterLeft(id) then out.setBalance(0, centerLeft)
      else if isCenterRight(id) then out.setBalance(0, centerRight)
      else out.setBalance(0, defaultBalance)
      nextFileChannel += 1
      out

  object Stem:
    val left: Stem = Stem("left", Out.instrumentSeparated, 0, 0)
    val right: Stem = Stem("right", Out.instrumentSeparated, 127, 0)
    // Can have high threshold, e.g., for "percussive" instrument like the grand piano
    val center: Stem = Stem("center", Out.instrumentCentered, centerBalance, config.centerThreshold)

    def allocateOut(id: String): Out =
      if isLeft(id) then left.allocateOut(id)
      else if isRight(id) then right.allocateOut(id)
      else center.allocateOut(id)

  // A case class to hold the mapping
  case class NeuronVoice(out: Out, note: Int)

  // Assign neurons to their respective stems
  val voiceById: Map[String, NeuronVoice] = config.notesById.map: (id, note) =>
    val out = Stem.allocateOut(id)
    id -> NeuronVoice(out, note)

  // Default to 0 so the first positive spike triggers a Note On
  private var priorVolumeById = Map.empty[String, (volume: Int, threshold: Int)].withDefaultValue((0, 0))

  private def voltageToVolume(v: Double): Int =
    val (from, to) = (Neuron.restVoltage, Neuron.thresholdVoltage)
    val range = to - from
    // Simple linear clamp -> [0, 127]
    val voltage = if config.useBelowRest then (v - from).abs + from else v
    val clamped = voltage.max(from).min(to)
    (((clamped - from) / range) * 127).round.toInt

  private val availableLiveChannels = mutable.Queue.from((0 to 8) ++ (10 to 15))
  private val activeLiveChannels = mutable.Map.empty[String, Int]

  def voiceNextFrame(step: Int, newState: Map[String, Neuron.State]): Unit =
    // Pre-calculate all new volumes and respective thresholds once
    val newVolumeById: Map[String, (volume: Int, threshold: Int)] = voiceById.map: (id, voice) =>
      id -> (voltageToVolume(newState(id).voltage), voice.out.volumeThresholdOn)

    // ==========================================
    // For file, output all voiced neurons
    // ==========================================
    voiceById.foreach: (id, voice) =>
      val currentVolume = newVolumeById(id).volume
      val previousVolume = priorVolumeById(id).volume
      val volumeThresholdOn = voice.out.volumeThresholdOn

      if currentVolume != previousVolume then

        // In all cases, including if the note is already on, adjust the volume
        voice.out.setVolume(step, currentVolume)

        if previousVolume <= volumeThresholdOn && currentVolume > volumeThresholdOn then
          // Turn the note on
          voice.out.noteOn(step, voice.note)

        else if previousVolume > volumeThresholdOn && currentVolume <= volumeThresholdOn then
          // Turn the note off
          voice.out.noteOff(step, voice.note)

    // ==========================================
    // For live preview, only output loudest 15 neurons
    // ==========================================

    // Find the rightful owners: the 15 loudest neurons
    val liveWinners: Set[String] = newVolumeById
      .filter((_, t) => t.volume > t.threshold) // Don't assign channels to silent neurons
      .toSeq
      .sortBy((id, t) => (-t.volume, id)) // Sort descending by volume, use the id as a tie-breaker
      .take(15)
      .map((id, _) => id)
      .toSet

    // Evict and silence active channels that are no longer winners
    activeLiveChannels.toList.foreach: (id, chan) =>
      if !liveWinners.contains(id) then
        Live.noteOff(chan, voiceById(id).note)
        activeLiveChannels.remove(id)
        availableLiveChannels.enqueue(chan)

    // Wire up new winners and adjust continuing winners
    liveWinners.foreach: id =>
      val currentVolume = newVolumeById(id).volume
      val previousVolume = priorVolumeById(id).volume

      activeLiveChannels.get(id) match
        // Existing winner - adjust the volume if it changed.
        case Some(chan) => if currentVolume != previousVolume then Live.setVolume(chan, currentVolume)

        // New winner - dequeue a freed channel and initialize it.
        case None =>
          val chan = availableLiveChannels.dequeue()
          activeLiveChannels(id) = chan

          val balance = if isLeft(id) then 0
          else if isRight(id) then 127
          else if isCenterLeft(id) then centerLeft
          else if isCenterRight(id) then centerRight
          else centerBalance
          val instrument = if isLeft(id) || isRight(id) then Out.instrumentSeparated else Out.instrumentCentered

          Live.setInstrument(chan, instrument)
          Live.setBalance(chan, balance)
          Live.setVolume(chan, currentVolume)
          Live.noteOn(chan, voiceById(id).note)

    // Save state for the next frame
    priorVolumeById = newVolumeById

  def close(lastStep: Int): Unit =
    voiceById.foreach: (_, voice) =>
      voice.out.setVolume(lastStep, 0)
      voice.out.noteOff(lastStep, voice.note)

    receiver.close() // Live is done

    stats.foreach: (note, count) =>
      println(s"$note\t$count")

    println(s"writing ${config.simulationName} stem files...")
    // Write three separate files
    MidiSystem.write(Stem.left.sequence, 1, java.io.File(s"$filePrefix-left.mid"))
    MidiSystem.write(Stem.right.sequence, 1, java.io.File(s"$filePrefix-right.mid"))
    MidiSystem.write(Stem.center.sequence, 1, java.io.File(s"$filePrefix-center.mid"))

//For debugging playback
//    val sequencer: Sequencer = MidiSystem.getSequencer
//    for (stem, i) <- Seq(Stem.center, Stem.left, Stem.right).zipWithIndex do
//      println(s"Playing back stem #$i preview...")
//      sequencer.open()
//      sequencer.setSequence(stem.sequence)
//      sequencer.start()
//      while sequencer.isRunning do Thread.sleep(100) // Keep the JVM alive long enough to hear the playback
//      sequencer.close()
//
//      println(s"Done playing back #$i")
//
//    println(s"Done playing back all")
