package com.example.adminmemo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArrayList

data class StudyTtsState(
    val playing: Boolean,
    val paused: Boolean,
    val index: Int,
    val ids: List<String>,
    val title: String,
    val pass: Int = 1,
    val repeat: Int = 1,
    val host: Boolean = false
)

object StudyTtsHub {
    @Volatile
    var state = StudyTtsState(false, false, -1, emptyList(), "")
        private set

    private val listeners = CopyOnWriteArrayList<(StudyTtsState) -> Unit>()

    fun addListener(l: (StudyTtsState) -> Unit) {
        listeners.add(l)
        l(state)
    }

    fun removeListener(l: (StudyTtsState) -> Unit) {
        listeners.remove(l)
    }

    fun publish(next: StudyTtsState) {
        state = next
        val copy = listeners.toList()
        Handler(Looper.getMainLooper()).post {
            copy.forEach { it(next) }
        }
    }
}

class StudyTtsService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_PLAY = "com.example.adminmemo.TTS_PLAY"
        const val ACTION_TOGGLE = "com.example.adminmemo.TTS_TOGGLE"
        const val ACTION_STOP = "com.example.adminmemo.TTS_STOP"
        const val ACTION_NEXT = "com.example.adminmemo.TTS_NEXT"
        const val ACTION_PREV = "com.example.adminmemo.TTS_PREV"
        const val EXTRA_IDS = "extra_tts_ids"
        const val EXTRA_INDEX = "extra_tts_index"
        const val EXTRA_HOST = "extra_tts_host"
        const val EXTRA_REPEAT = "extra_tts_repeat"
        const val EXTRA_HOST_DATE = "extra_tts_host_date"
        private const val CHANNEL = "study_tts"
        private const val NOTIF_ID = 71
        private const val DONE_PREFIX = "done_"
        private const val CUE_PREFIX = "cue_"

        fun play(
            context: Context,
            ids: List<String>,
            index: Int,
            host: Boolean = false,
            repeat: Int = -1,
            hostDate: String = ""
        ) {
            val intent = Intent(context, StudyTtsService::class.java).apply {
                action = ACTION_PLAY
                putStringArrayListExtra(EXTRA_IDS, ArrayList(ids))
                putExtra(EXTRA_INDEX, index)
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_REPEAT, repeat)
                putExtra(EXTRA_HOST_DATE, hostDate)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun toggle(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, StudyTtsService::class.java).setAction(ACTION_TOGGLE)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, StudyTtsService::class.java).setAction(ACTION_STOP))
        }

        fun skip(context: Context, delta: Int) {
            val action = if (delta >= 0) ACTION_NEXT else ACTION_PREV
            ContextCompat.startForegroundService(
                context,
                Intent(context, StudyTtsService::class.java).setAction(action)
            )
        }

        fun applyVoice(tts: TextToSpeech, context: Context, radio: Boolean = false) {
            TtsVoices.apply(tts, context, radio)
        }

        fun koreanVoices(tts: TextToSpeech): List<Voice> = TtsVoices.korean(tts)

        fun voiceLabel(voice: Voice): String = TtsVoices.label(voice)
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ids: List<String> = emptyList()
    private var index = 0
    private var playing = false
    private var paused = false
    private var pendingSpeak = false
    private var pass = 1
    private var hostMode = false
    private var hostStyle = 0
    private var sessionRepeat = -1
    private var hostDate = ""
    private var engineName: String? = TtsVoices.GOOGLE
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        tts = TtsVoices.create(this, this, engineName)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            val next = TtsVoices.nextEngine(engineName)
            if (next != null) {
                tts?.shutdown()
                engineName = next.ifBlank { null }
                tts = TtsVoices.create(this, this, engineName)
                return
            }
            ttsReady = false
            stopSelfSafely()
            return
        }
        ttsReady = true
        val engine = tts ?: return
        applyVoice(engine, this, hostMode)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId?.startsWith(CUE_PREFIX) == true) playCue()
            }
            override fun onDone(utteranceId: String?) {
                if (utteranceId?.startsWith(DONE_PREFIX) == true && playing && !paused) {
                    Handler(Looper.getMainLooper()).post { onCardFinished() }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Handler(Looper.getMainLooper()).post { pauseInternal() }
            }
        })
        if (pendingSpeak) {
            pendingSpeak = false
            speakCurrent()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        when (intent?.action) {
            ACTION_PLAY -> {
                ids = intent.getStringArrayListExtra(EXTRA_IDS) ?: arrayListOf()
                index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, maxOf(0, ids.lastIndex))
                hostMode = intent.getBooleanExtra(EXTRA_HOST, false)
                sessionRepeat = intent.getIntExtra(EXTRA_REPEAT, -1)
                hostDate = intent.getStringExtra(EXTRA_HOST_DATE).orEmpty()
                if (hostMode) hostStyle = TtsHostScripts.roll(hostStyle)
                pass = 1
                paused = false
                playing = true
                startAsForeground()
                if (ttsReady) speakCurrent() else pendingSpeak = true
            }
            ACTION_TOGGLE -> {
                if (playing && !paused) pauseInternal() else resumeInternal()
            }
            ACTION_NEXT -> skip(1)
            ACTION_PREV -> skip(-1)
            ACTION_STOP -> stopSelfSafely()
            else -> startAsForeground()
        }
        return START_NOT_STICKY
    }

    private fun skip(delta: Int) {
        if (ids.isEmpty()) return
        val next = index + delta
        if (next !in ids.indices) {
            if (delta > 0) stopSelfSafely()
            return
        }
        index = next
        pass = 1
        paused = false
        playing = true
        startAsForeground()
        if (ttsReady) speakCurrent() else pendingSpeak = true
    }

    private fun effectiveRepeat(): Int =
        if (sessionRepeat > 0) sessionRepeat else AppPrefs.getTtsRepeat(this)

    private fun onCardFinished() {
        val times = effectiveRepeat()
        if (pass < times) {
            pass++
            if (ttsReady) speakCurrent(pauseFirst = true) else pendingSpeak = true
            return
        }
        pass = 1
        if (index >= ids.lastIndex) {
            stopSelfSafely()
        } else {
            skip(1)
        }
    }

    private fun resumeInternal() {
        if (ids.isEmpty()) {
            stopSelfSafely()
            return
        }
        paused = false
        playing = true
        startAsForeground()
        if (ttsReady) speakCurrent() else pendingSpeak = true
    }

    private fun pauseInternal() {
        playing = false
        paused = true
        tts?.stop()
        releaseWakeLock()
        publish()
        startAsForeground()
    }

    private fun speakCurrent(pauseFirst: Boolean = false) {
        val engine = tts ?: return
        applyVoice(engine, this, hostMode)
        val card = currentCard() ?: run {
            stopSelfSafely()
            return
        }
        if (!requestFocus()) {
            pauseInternal()
            return
        }
        acquireWakeLock()
        val pieces = buildQaPieces(card, pauseFirst)
        if (pieces.isEmpty() || pieces.all { it is TtsPiece.Silence || it is TtsPiece.Cue }) {
            pass = 1
            if (index >= ids.lastIndex) stopSelfSafely() else skip(1)
            return
        }
        pieces.forEachIndexed { i, piece ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val last = i == pieces.lastIndex
            val id = when {
                last -> "$DONE_PREFIX$index"
                piece is TtsPiece.Cue -> "$CUE_PREFIX${index}_$i"
                else -> "part_${index}_$i"
            }
            when (piece) {
                is TtsPiece.Speech -> engine.speak(TtsVoices.spoken(piece.text), mode, null, id)
                is TtsPiece.Silence -> engine.playSilentUtterance(piece.ms, mode, id)
                is TtsPiece.Cue -> engine.playSilentUtterance(320L, mode, id)
            }
        }
        playing = true
        paused = false
        publish()
        startAsForeground()
    }

    private fun buildQaPieces(card: Card, pauseFirst: Boolean): MutableList<TtsPiece> {
        val pieces = mutableListOf<TtsPiece>()
        if (pauseFirst) pieces.add(TtsPiece.Silence(650L))
        pieces.addAll(hostLeadPieces(card))
        pieces.addAll(speechPieces(questionLine(card)))
        pieces.add(TtsPiece.Silence(AppPrefs.getTtsThinkSeconds(this) * 1000L))
        if (AppPrefs.getTtsCueEnabled(this)) pieces.add(TtsPiece.Cue)
        if (hostMode) {
            pieces.add(TtsPiece.Silence(220L))
            pieces.add(TtsPiece.Speech(TtsHostScripts.bodyLead(hostStyle)))
            pieces.add(TtsPiece.Silence(280L))
        }
        pieces.addAll(speechPieces(answerScript(card)))
        val lastPass = pass >= effectiveRepeat()
        if (hostMode && lastPass && index >= ids.lastIndex) {
            TtsHostScripts.closing(hostStyle).forEach { line ->
                pieces.add(TtsPiece.Silence(280L))
                pieces.add(TtsPiece.Speech(line))
            }
        }
        return pieces
    }

    private fun hostLeadPieces(card: Card): List<TtsPiece> {
        if (!hostMode) return emptyList()
        val sub = canonicalizeSubject(card.subject)
        val past = hostDate.isNotBlank() && hostDate != TodayTtsStore.todayKey()
        val out = mutableListOf<TtsPiece>()
        when {
            index == 0 && pass == 1 -> {
                TtsHostScripts.greet(past, ids.size, hostStyle, TodayTtsStore.displayDate(hostDate)).forEach { line ->
                    out.add(TtsPiece.Speech(line))
                    out.add(TtsPiece.Silence(260L))
                }
                out.add(TtsPiece.Speech(TtsHostScripts.firstCard(sub, hostStyle)))
            }
            pass == 1 -> {
                out.add(TtsPiece.Speech(TtsHostScripts.nextCard(index, sub, hostStyle)))
            }
            else -> {
                out.add(TtsPiece.Speech(TtsHostScripts.repeatCard(hostStyle)))
            }
        }
        out.add(TtsPiece.Silence(280L))
        return out
    }

    private fun questionLine(card: Card): String {
        val title = card.topicTitle.ifBlank { card.title }.trim()
        return if (hostMode) {
            TtsHostScripts.titleLine(title, hostStyle)
        } else {
            "${title}에 대해 설명하시오."
        }
    }

    private fun answerScript(card: Card): String {
        val parts = mutableListOf<String>()
        if (card.mnemonic.isNotBlank()) {
            parts.add("두문자. ${card.mnemonic.replace(".", " ")}")
        }
        val body = displayStudyBody(
            card.back.ifBlank { "본문 내용이 없어요" },
            CardStore.isLocallyEdited(this, card.id)
        )
        parts.add(body)
        return parts.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun playCue() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 75)
            tg.startTone(
                if (hostMode) ToneGenerator.TONE_CDMA_PIP else ToneGenerator.TONE_PROP_BEEP,
                if (hostMode) 140 else 180
            )
            Handler(Looper.getMainLooper()).postDelayed({
                try { tg.release() } catch (_: Exception) {}
            }, 280)
        } catch (_: Exception) {
        }
    }

    private fun currentCard(): Card? {
        if (ids.isEmpty() || index !in ids.indices) return null
        val id = ids[index]
        return CardStore.getAllCards(this).firstOrNull { it.id == id }
    }

    private sealed class TtsPiece {
        data class Speech(val text: String) : TtsPiece()
        data class Silence(val ms: Long) : TtsPiece()
        data object Cue : TtsPiece()
    }

    /** 줄·쉼표·마침표마다 끊어서 엔진이 문장 억양을 다시 붙이게 한다. */
    private fun speechPieces(text: String): List<TtsPiece> {
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val out = mutableListOf<TtsPiece>()
        var blankRun = 0
        for (raw in lines) {
            val line = raw.replace(Regex("[\\t ]+"), " ").trim()
            if (line.isEmpty()) {
                blankRun++
                continue
            }
            if (out.isNotEmpty()) {
                out.add(TtsPiece.Silence(if (blankRun > 0) 720L else 380L))
            }
            blankRun = 0
            val phrases = splitPhrases(line)
            phrases.forEachIndexed { i, phrase ->
                if (i > 0) {
                    val prev = phrases[i - 1].lastOrNull() ?: ' '
                    out.add(
                        TtsPiece.Silence(
                            when (prev) {
                                in ".!?。！？" -> 340L
                                in ",，、;" -> 150L
                                else -> 110L
                            }
                        )
                    )
                }
                out.add(TtsPiece.Speech(phrase))
            }
        }
        return out
    }

    private fun splitPhrases(text: String): List<String> {
        val cleaned = TtsVoices.spoken(text)
        if (cleaned.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        fun flush() {
            val t = buf.toString().trim()
            if (t.isNotEmpty()) out.add(t)
            buf.clear()
        }
        val max = 68
        for (c in cleaned) {
            buf.append(c)
            val punct = c in ".!?。！？,，、;"
            if (punct || (buf.length >= max && c == ' ')) flush()
        }
        flush()
        return if (out.isEmpty()) listOf(cleaned) else out
    }

    private fun publish() {
        StudyTtsHub.publish(
            StudyTtsState(
                playing = playing && !paused,
                paused = paused,
                index = index,
                ids = ids,
                title = currentCard()?.topicTitle.orEmpty(),
                pass = pass,
                repeat = effectiveRepeat(),
                host = hostMode
            )
        )
    }

    private fun startAsForeground() {
        val card = currentCard()
        val title = card?.topicTitle?.ifBlank { "본문 듣기" } ?: "본문 듣기"
        val times = effectiveRepeat()
        val passBit = if (times > 1) "  ·  ${pass}/${times}회" else ""
        val label = if (hostMode) "오늘의 듣기" else "본문 듣기"
        val status = when {
            playing && !paused -> "$label  ${index + 1} / ${ids.size}$passBit"
            paused -> "일시정지  ${index + 1} / ${ids.size}$passBit"
            else -> label
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            if (hostMode) {
                Intent(this, TodayTtsActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            } else {
                Intent(this, ContentDetailActivity::class.java).apply {
                    putStringArrayListExtra(ContentDetailActivity.EXTRA_IDS, ArrayList(ids))
                    putExtra(ContentDetailActivity.EXTRA_INDEX, index)
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            },
            pendingFlags()
        )
        val toggle = pendingService(ACTION_TOGGLE, 1)
        val stop = pendingService(ACTION_STOP, 2)
        val next = pendingService(ACTION_NEXT, 3)
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(title)
            .setContentText(status)
            .setContentIntent(open)
            .setOngoing(playing && !paused)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                if (playing && !paused) "일시정지" else "이어듣기",
                toggle
            )
            .addAction(android.R.drawable.ic_media_next, "다음", next)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "종료", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun pendingService(action: String, req: Int): PendingIntent {
        return PendingIntent.getService(
            this,
            req,
            Intent(this, StudyTtsService::class.java).setAction(action),
            pendingFlags()
        )
    }

    private fun pendingFlags(): Int {
        return PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL, "본문 듣기", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        mgr.createNotificationChannel(ch)
    }

    private fun requestFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        focusRequest = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "adminmemo:tts").also {
            it.setReferenceCounted(false)
            it.acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun stopSelfSafely() {
        playing = false
        paused = false
        tts?.stop()
        releaseWakeLock()
        abandonFocus()
        StudyTtsHub.publish(StudyTtsState(false, false, index, ids, currentCard()?.topicTitle.orEmpty()))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        releaseWakeLock()
        abandonFocus()
        super.onDestroy()
    }
}
