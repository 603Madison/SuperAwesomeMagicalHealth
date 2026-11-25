package com.health.app.ui.meditation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.health.api.MeditationSession
import com.health.app.R
import com.health.app.attempt
import com.health.app.container
import com.health.app.net.addMeditationSession
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/*
 * The meditation playing, screen off and all: a foreground service (media playback) with a quiet
 * ongoing notification (Pause and Stop). It holds the phone awake just enough for the timing to
 * keep going, asks for audio focus for a while (others turn down; a call pauses it), reads aloud
 * with the phone's own offline voice, and chimes with a tone made here. When it ends, or is
 * stopped after a minute or more, the session is sent to the person's server, and nowhere else.
 */

/** The meditation playing now (or just ended), for the player's screen; one at a time. */
object MeditationPlayback {
    private val _state = MutableStateFlow<PlayerState?>(null)
    val state: StateFlow<PlayerState?> = _state.asStateFlow()

    private val _saved = MutableStateFlow<MeditationSession?>(null)

    /** The session as the server kept it, once sent: how it felt can be added to it. */
    val saved: StateFlow<MeditationSession?> = _saved.asStateFlow()

    internal var engine: MeditationEngine? = null

    internal fun show(state: PlayerState?) {
        _state.value = state
    }

    internal fun saved(session: MeditationSession?) {
        _saved.value = session
    }

    fun pause() = engine?.pause()
    fun resume() = engine?.resume()
    fun stop() = engine?.stop()
    fun setVoice(on: Boolean) = engine?.setVoice(on)
    fun setFadeOut(on: Boolean) = engine?.setFadeOut(on)

    /** Whether one's playing (or paused) now. */
    val playing: Boolean get() = _state.value?.phase.let { it != null && it != PlayerPhase.ENDED }

    /** The player closed after it ended: nothing to show any more. */
    fun forget() {
        if (playing) return
        _state.value = null
        _saved.value = null
    }

    /** Only sessions of a minute or more are kept, besides any heard to the end. */
    fun keeps(state: PlayerState): Boolean = state.finished || state.activeMs >= 60_000L

    /** The session to send for [state]. */
    fun session(state: PlayerState): MeditationSession = MeditationSession(
        startedAt = state.startedAt,
        minutes = state.minutesSpent.coerceIn(0.01, 600.0),
        finished = state.finished,
        meditationId = state.meditationId,
    )
}

class MeditationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playing: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var voice: DeviceVoice? = null
    private var focus: AudioFocusRequest? = null
    private var pausedForFocus = false
    private var noisy: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> MeditationPlayback.pause()
            ACTION_RESUME -> MeditationPlayback.resume()
            ACTION_STOP -> {
                MeditationPlayback.stop()
                if (MeditationPlayback.engine == null) finish()
            }
            ACTION_START -> start(intent)
            // Started again by Android after it closed the app: there's nothing to carry on with.
            else -> finish()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID)
        val meditation = id?.let { MeditationStore(this).meditation(it) }
        // Foreground first, always: Android expects it of a service started to be one.
        val shown = attempt {
            ServiceCompat.startForeground(this, NOTIFICATION, MeditationNotice.build(this, meditation?.title ?: "Meditation", null), TYPE)
        }
        if (shown.isFailure || meditation == null || MeditationPlayback.playing) {
            shown.exceptionOrNull()?.let { Log.w(TAG, "Couldn't keep playing in the background", it) }
            if (!MeditationPlayback.playing) finish()
            return
        }
        val store = MeditationStore(this)
        val wantVoice = intent.getBooleanExtra(EXTRA_VOICE, store.voice)
        val fadeOut = intent.getBooleanExtra(EXTRA_FADE, store.fadeOut)
        MeditationPlayback.saved(null)
        keepAwake(meditation.minutes)
        playing = scope.launch {
            val opened = withTimeoutOrNull(OPEN_VOICE_MS) { DeviceVoice.open(this@MeditationService) }
            voice = opened
            askForFocus()
            listenForNoisy()
            val engine = MeditationEngine(
                meditation, opened, ToneChimes, System::currentTimeMillis,
                if (wantVoice) VoiceMode.VOICE else VoiceMode.TEXT_ONLY, fadeOut,
                onEnd = ::ended,
            )
            MeditationPlayback.engine = engine
            store.lastId = meditation.id
            store.lastStartedAt = System.currentTimeMillis()
            launch { engine.state.collect { MeditationPlayback.show(it) } }
            launch {
                engine.state.map { MeditationNotice.words(it) }.distinctUntilChanged().collect { (title, text) ->
                    if (MeditationNotice.canNotify(this@MeditationService)) attempt { MeditationNotice.show(this@MeditationService, title, text, engine.state.value.phase) }
                }
            }
            engine.start(this)
        }
    }

    /** It ended, or was stopped: the session goes to the server (a minute or more, or heard to the end), and the service goes. */
    private fun ended(state: PlayerState) {
        MeditationPlayback.show(state)
        MeditationPlayback.engine = null
        if (MeditationPlayback.keeps(state)) send(state)
        // After the last chime has had its moment.
        scope.launch {
            delay(300)
            finish()
        }
    }

    /** In the app's scope, so the service going doesn't cut it short; tried once more if the server can't be reached. */
    private fun send(state: PlayerState) {
        val c = applicationContext.container
        if (!c.settings.value.signedIn) return
        val session = MeditationPlayback.session(state)
        c.scope.launch {
            val saved = attempt { c.api.addMeditationSession(session) }.getOrNull() ?: run {
                delay(RETRY_MS)
                attempt { c.api.addMeditationSession(session) }.onFailure { Log.w(TAG, "Couldn't save the meditation: ${it.javaClass.simpleName}") }.getOrNull()
            }
            if (saved != null) {
                MeditationPlayback.saved(saved)
                c.dataChanged()
            }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            // A call, or something else that needs to be heard for a moment: pause, and go on after.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (MeditationPlayback.state.value?.phase == PlayerPhase.PLAYING) {
                pausedForFocus = true
                MeditationPlayback.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedForFocus = false
                MeditationPlayback.pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedForFocus) {
                pausedForFocus = false
                MeditationPlayback.resume()
            }
        }
    }

    /** Asks to be heard for a while: other sound turns down meanwhile. */
    private fun askForFocus() {
        val audio = getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(ATTRIBUTES)
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(false)
            .build()
        focus = request
        attempt { audio.requestAudioFocus(request) }
    }

    /** Headphones taken out: it pauses rather than speak out loud in the room. */
    private fun listenForNoisy() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) MeditationPlayback.pause()
            }
        }
        noisy = receiver
        attempt { ContextCompat.registerReceiver(this, receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED) }
    }

    /** Awake (the screen may be off) for as long as it could take, with room for pauses. */
    private fun keepAwake(minutes: Int) {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "health:meditation")
            ?.apply { setReferenceCounted(false) }
            ?.also { wakeLock = it }
        attempt { lock?.acquire((minutes + 30) * 60_000L) }
    }

    private fun finish() {
        MeditationPlayback.engine?.stop()
        MeditationPlayback.engine = null
        playing?.cancel()
        playing = null
        voice?.close()
        voice = null
        focus?.let { f -> attempt { getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(f) } }
        focus = null
        noisy?.let { attempt { unregisterReceiver(it) } }
        noisy = null
        attempt { wakeLock?.takeIf { it.isHeld }?.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        MeditationPlayback.engine?.stop()
        MeditationPlayback.engine = null
        voice?.close()
        scope.cancel()
        attempt { wakeLock?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Meditation"
        const val ACTION_START = "com.health.app.meditation.START"
        const val ACTION_PAUSE = "com.health.app.meditation.PAUSE"
        const val ACTION_RESUME = "com.health.app.meditation.RESUME"
        const val ACTION_STOP = "com.health.app.meditation.STOP"
        const val EXTRA_ID = "id"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_FADE = "fadeOut"
        /** Its own: the strap's is 4401, the reminders' 45xx. */
        const val NOTIFICATION = 4520
        private const val OPEN_VOICE_MS = 8_000L
        private const val RETRY_MS = 10_000L

        private val TYPE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0

        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        /** Starts [id] (the screen's on: the person pressed Start). */
        fun start(context: Context, id: String, voice: Boolean, fadeOut: Boolean): Boolean = attempt {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MeditationService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_ID, id).putExtra(EXTRA_VOICE, voice).putExtra(EXTRA_FADE, fadeOut),
            )
        }.onFailure { Log.w(TAG, "Couldn't start the meditation", it) }.isSuccess

        fun intent(context: Context, action: String): PendingIntent = PendingIntent.getService(
            context, action.hashCode(), Intent(context, MeditationService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** The quiet ongoing notification while one plays: what it is, the time left, Pause (or Resume) and Stop. */
object MeditationNotice {
    private const val CHANNEL = "meditation-playing"

    fun words(state: PlayerState): Pair<String, String> = state.title to when (state.phase) {
        PlayerPhase.PAUSED -> "Paused"
        PlayerPhase.ENDED -> "Ending"
        PlayerPhase.PLAYING -> MeditationPlan.leftText(state.secondsLeft)
    }

    fun canNotify(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun build(context: Context, title: String, text: String?, phase: PlayerPhase = PlayerPhase.PLAYING): Notification {
        channel(context)
        val open = PendingIntent.getActivity(
            context, MeditationService.NOTIFICATION,
            Intent(context, MeditationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .apply { text?.let(::setContentText) }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Meditation").build())
            .setContentIntent(open)
            .apply {
                if (phase == PlayerPhase.PAUSED) addAction(0, "Resume", MeditationService.intent(context, MeditationService.ACTION_RESUME))
                else addAction(0, "Pause", MeditationService.intent(context, MeditationService.ACTION_PAUSE))
            }
            .addAction(0, "Stop", MeditationService.intent(context, MeditationService.ACTION_STOP))
            .build()
    }

    fun show(context: Context, title: String, text: String, phase: PlayerPhase) {
        @Suppress("MissingPermission") // canNotify checked
        NotificationManagerCompat.from(context).notify(MeditationService.NOTIFICATION, build(context, title, text, phase))
    }

    private fun channel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Meditation playing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while a meditation plays, with Pause and Stop."
                setSound(null, null)
                setShowBadge(false)
            },
        )
    }
}

/** The start and end chime: a soft tone made on the phone, played once. */
object ToneChimes : Chimes {
    private val samples by lazy { Chime.samples() }

    override suspend fun chime() {
        val track = withContext(Dispatchers.IO) {
            attempt {
                AudioTrack.Builder()
                    .setAudioAttributes(MeditationService.ATTRIBUTES)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Chime.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(samples.size * 2)
                    .build()
                    .apply { write(samples, 0, samples.size) }
            }.getOrNull()
        } ?: return
        try {
            attempt { track.play() }
            delay(samples.size * 1000L / Chime.RATE + 100)
        } finally {
            attempt { track.stop() }
            track.release()
        }
    }
}

/**
 * The phone's own text-to-speech, offline only: a voice that needs no network, network synthesis
 * asked not to be used, read a little slower and lower than usual. [open] gives none when the phone
 * has no offline voice, and the player shows the words instead.
 */
class DeviceVoice private constructor(private val tts: TextToSpeech) : Speaker {
    private val waiting = HashMap<String, CancellableContinuation<Boolean>>()

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish(utteranceId, true)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId, false)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, false)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId, false)
        })
    }

    private fun finish(id: String?, done: Boolean) {
        val cont = synchronized(waiting) { id?.let { waiting.remove(it) } } ?: return
        if (cont.isActive) cont.resume(done)
    }

    override suspend fun say(text: String): Boolean = suspendCancellableCoroutine { cont ->
        val id = UUID.randomUUID().toString()
        synchronized(waiting) { waiting[id] = cont }
        cont.invokeOnCancellation { synchronized(waiting) { waiting.remove(id) } }
        val params = Bundle().apply {
            // Never the network: nothing read aloud leaves the phone.
            @Suppress("DEPRECATION")
            putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "false")
        }
        val queued = attempt { tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) }.getOrDefault(TextToSpeech.ERROR)
        if (queued != TextToSpeech.SUCCESS) finish(id, false)
    }

    override fun stop() {
        attempt { tts.stop() }
        // Some engines don't say they've stopped: whatever's waiting goes on.
        val all = synchronized(waiting) { waiting.values.toList().also { waiting.clear() } }
        all.forEach { if (it.isActive) it.resume(false) }
    }

    fun close() {
        stop()
        attempt { tts.shutdown() }
    }

    companion object {
        const val RATE = 0.85f
        const val PITCH = 0.95f

        /** The phone's voices, as [VoicePick] sees them. */
        fun options(voices: Collection<Voice>): List<VoiceOption> = voices.map { v ->
            VoiceOption(
                name = v.name, locale = v.locale, network = v.isNetworkConnectionRequired,
                installed = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty(),
                quality = v.quality, latency = v.latency,
            )
        }

        /** The phone's text-to-speech with an offline voice set, or null when there's none. */
        suspend fun open(context: Context): DeviceVoice? {
            var engine: TextToSpeech? = null
            val ready = suspendCancellableCoroutine { cont ->
                engine = TextToSpeech(context.applicationContext) { status -> if (cont.isActive) cont.resume(status == TextToSpeech.SUCCESS) }
                cont.invokeOnCancellation { attempt { engine?.shutdown() } }
            }
            val tts = engine ?: return null
            if (!ready) {
                attempt { tts.shutdown() }
                return null
            }
            val all = attempt { tts.voices.orEmpty() }.getOrDefault(emptySet())
            val default = attempt { tts.defaultVoice?.name }.getOrNull()
            val chosen = VoicePick.choose(options(all), Locale.getDefault(), default)?.let { pick -> all.firstOrNull { it.name == pick.name } }
            val set = chosen != null && attempt { tts.setVoice(chosen) }.getOrDefault(TextToSpeech.ERROR) == TextToSpeech.SUCCESS
            if (!set) {
                attempt { tts.shutdown() }
                return null
            }
            attempt {
                tts.setSpeechRate(RATE)
                tts.setPitch(PITCH)
                tts.setAudioAttributes(MeditationService.ATTRIBUTES)
            }
            return DeviceVoice(tts)
        }
    }
}
