package com.health.app.ui.modules.transition.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.health.app.attempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * The trainer's sound: listening (AudioRecord at 16 kHz, analysed on a background thread as it
 * comes in), target tones for matching (AudioTrack), and, only if the person turns it on,
 * recordings kept on this phone (never uploaded, never backed up: noBackupFilesDir, and the
 * backup rules exclude it too). Nothing here ever sends sound anywhere.
 */

/** Whether the app may use the microphone. */
fun micAllowed(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/**
 * Listens to the microphone and measures each frame on a background thread. One take at a time:
 * [start] begins one (optionally recording it to a file), [stop] ends it and hands back its frames.
 */
class VoiceListener(private val speakingDb: () -> Double? = { null }) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val frames = ArrayList<VoiceFrame>()

    private val _live = MutableStateFlow(VoiceLive())
    val live: StateFlow<VoiceLive> = _live.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /** Called on the listening thread for every frame (for matching notes). */
    @Volatile
    var onFrame: ((VoiceFrame, VoiceLive) -> Unit)? = null

    /** While paused (a tone playing), what the microphone hears is left out. */
    @Volatile
    var paused = false

    val running: Boolean get() = job?.isActive == true

    @SuppressLint("MissingPermission")
    fun start(context: Context, recordTo: File? = null) {
        if (running || !micAllowed(context)) return
        synchronized(frames) { frames.clear() }
        _problem.value = null
        _live.value = VoiceLive(listening = true)
        paused = false
        job = scope.launch {
            val opened = open() ?: run {
                _problem.value = "The microphone couldn't be opened. Another app may be using it."
                _live.value = VoiceLive()
                return@launch
            }
            val (record, rate) = opened
            val resampler = if (rate != VoiceScale.RATE) StreamResampler(rate, VoiceScale.RATE) else null
            val analyzer = VoiceAnalyzer()
            val meter = LiveMeter(speakingDb())
            val buffer = FloatArray(rate / 50)
            val wav = recordTo?.let { attempt { WavWriter(it, VoiceScale.RATE) }.getOrNull() }
            try {
                record.startRecording()
                while (isActive) {
                    val n = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (n < 0) {
                        _problem.value = "The microphone stopped. Try again in a moment."
                        break
                    }
                    if (n == 0 || paused) continue
                    val pcm = resampler?.push(buffer, n) ?: buffer.copyOf(n)
                    wav?.write(pcm, pcm.size)
                    analyzer.push(pcm, pcm.size) { frame ->
                        synchronized(frames) { frames += frame }
                        val live = meter.add(frame)
                        _live.value = live
                        onFrame?.invoke(frame, live)
                    }
                }
            } finally {
                attempt { record.stop() }
                record.release()
                attempt { wav?.close() }
                buffer.fill(0f)
            }
        }
    }

    /** Ends the take and hands back what was heard (any recording is closed by then). */
    suspend fun stop(): List<VoiceFrame> {
        job?.cancelAndJoin()
        job = null
        _live.value = VoiceLive()
        return synchronized(frames) { frames.toList().also { frames.clear() } }
    }

    /** What's been heard so far in this take. */
    fun heard(): List<VoiceFrame> = synchronized(frames) { frames.toList() }

    fun close() {
        job?.cancel()
        scope.cancel()
    }

    /** The voice-recognition input (no automatic gain, as Android asks of it), at 16 kHz if it can, else 48 or 44.1. */
    @SuppressLint("MissingPermission")
    private fun open(): Pair<AudioRecord, Int>? {
        for (source in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            for (rate in listOf(VoiceScale.RATE, 48_000, 44_100)) {
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                if (min <= 0) continue
                val record = attempt {
                    AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, rate / 5 * 4))
                }.getOrNull() ?: continue
                if (record.state == AudioRecord.STATE_INITIALIZED) return record to rate
                record.release()
            }
        }
        return null
    }
}

/** Plays target tones for matching. */
object TonePlayer {
    private const val RATE = 44_100

    /** Plays [hz] for [seconds] and returns when it's done. */
    suspend fun play(hz: Double, seconds: Double = 1.2) = withContext(Dispatchers.Default) {
        val samples = ToneSynth.samples(hz, RATE, seconds)
        val track = attempt {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 4)
                .build()
        }.getOrNull() ?: return@withContext
        try {
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            delay((seconds * 1000).toLong() + 60)
        } finally {
            attempt { track.stop() }
            track.release()
        }
    }
}

/** Writes 16-bit mono WAV as it comes in; the sizes are filled in on [close]. */
class WavWriter(file: File, private val rate: Int) : Closeable {
    private val out = RandomAccessFile(file, "rw").apply { setLength(0) }
    private var bytes = 0

    init {
        out.write(header(0))
    }

    fun write(samples: FloatArray, count: Int) {
        val buffer = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) buffer.putShort((samples[i].coerceIn(-1f, 1f) * 32_767).toInt().toShort())
        out.write(buffer.array())
        bytes += count * 2
    }

    override fun close() {
        out.seek(0)
        out.write(header(bytes))
        out.close()
    }

    private fun header(data: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(data)
    }.array()
}

/** A recording kept on this phone: the baseline, or an exercise. */
data class Recording(val file: File, val at: Long, val exerciseId: String, val seconds: Int) {
    val baseline: Boolean get() = exerciseId == VoiceRecordings.BASELINE
}

/**
 * Recordings, kept only on this phone, in app-private storage that's never backed up
 * (noBackupFilesDir/voice-recordings) and never uploaded. Deleted on signing out, or whenever the
 * person asks.
 */
class VoiceRecordings(context: Context) {
    private val dir = File(context.noBackupFilesDir, DIR)

    fun newFile(exerciseId: String, at: Long = System.currentTimeMillis()): File {
        dir.mkdirs()
        return File(dir, "${at}_${exerciseId.replace(Regex("[^A-Za-z0-9._-]"), "-")}.wav")
    }

    fun list(): List<Recording> = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }.orEmpty().mapNotNull { f ->
        val stem = f.name.removeSuffix(".wav")
        val at = stem.substringBefore('_').toLongOrNull() ?: return@mapNotNull null
        val seconds = ((f.length() - 44).coerceAtLeast(0) / (VoiceScale.RATE * 2)).toInt()
        if (seconds < 1) return@mapNotNull null
        Recording(f, at, stem.substringAfter('_'), seconds)
    }.sortedByDescending { it.at }

    /** The latest baseline recording, if one was kept. */
    fun baseline(): Recording? = list().firstOrNull { it.baseline }

    fun delete(recording: Recording) {
        recording.file.delete()
    }

    fun deleteAll() {
        dir.listFiles().orEmpty().forEach { it.delete() }
    }

    companion object {
        const val DIR = "voice-recordings"
        const val BASELINE = "baseline"
    }
}

/** Plays one recording back at a time. */
class ClipPlayer {
    private var player: MediaPlayer? = null

    private val _playing = MutableStateFlow<File?>(null)
    val playing: StateFlow<File?> = _playing.asStateFlow()

    fun play(file: File) {
        stop()
        val p = MediaPlayer()
        attempt {
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener { stop() }
            p.prepare()
            p.start()
            player = p
            _playing.value = file
        }.onFailure { p.release() }
    }

    fun stop() {
        player?.let { attempt { it.stop() }; it.release() }
        player = null
        _playing.value = null
    }
}
