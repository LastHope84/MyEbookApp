package com.example.myebookapp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Html
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import kotlin.math.abs

enum class TtsState { IDLE, PLAYING, PAUSED }

/**
 * Reads the publication aloud with the phone's speech engine.
 * Text comes from the book itself; the reader follows along by navigating to
 * the spoken position within each resource.
 */
class TtsController(
    private val context: Context,
    private val publication: Publication,
    private val scope: CoroutineScope,
    private val onStateChanged: (TtsState) -> Unit,
    private val onNavigate: (Locator) -> Unit,
    private val onChapterChanged: (Link) -> Unit,
    private val onError: (String) -> Unit
) {
    private data class Chunk(val text: String, val start: Int, val end: Int)

    private var engine: TextToSpeech? = null
    private var ready = false
    private var pendingStart: (() -> Unit)? = null
    private var job: Job? = null
    private var linkIndex = 0
    private var text = ""
    private var chunks: List<Chunk> = emptyList()
    private var chunkIndex = 0
    private var base: Locator? = null
    private var lastProgress = 0.0
    private var state = TtsState.IDLE
    private var currentUtterance = ""
    private var utteranceSeq = 0
    private var skipGuard = 0
    private var focusRequest: AudioFocusRequest? = null
    private var lastSpeakKey = ""
    private var lastSpeakAt = 0L
    private var loadedLinkIndex = -1

    val currentState: TtsState get() = state

    fun start(from: Locator) {
        loadedLinkIndex = -1
        base = from
        val order = publication.readingOrder
        linkIndex = order.indexOfFirst { it.href.toString() == from.href.toString() }.let { if (it < 0) 0 else it }
        val progression = from.locations.progression ?: 0.0
        ensureEngine { loadChapter(linkIndex, progression) }
    }

    fun pause() {
        if (state != TtsState.PLAYING) return
        LibraryScanner.logTts(context, "pause")
        engine?.stop()
        currentUtterance = ""
        lastSpeakKey = ""
        abandonFocus()
        setState(TtsState.PAUSED)
    }

    fun resume() {
        if (state != TtsState.PAUSED) return
        LibraryScanner.logTts(context, "resume")
        speakCurrent()
    }

    fun stop() {
        job?.cancel()
        job = null
        loadedLinkIndex = -1
        engine?.stop()
        currentUtterance = ""
        lastSpeakKey = ""
        abandonFocus()
        setState(TtsState.IDLE)
    }

    fun release() {
        stop()
        engine?.shutdown()
        engine = null
        ready = false
    }

    private fun ensureEngine(then: () -> Unit) {
        val existing = engine
        if (existing != null && ready) {
            then()
            return
        }
        pendingStart = then
        if (existing != null) return
        engine = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) {
                onError("Text-to-speech is not available on this phone. Install or enable a speech engine such as Google Text-to-Speech.")
                setState(TtsState.IDLE)
                return@TextToSpeech
            }
            val instance = engine
            instance?.setOnUtteranceProgressListener(progressListener)
            if (!TtsPrefs.apply(instance, context)) {
                onError("The chosen voice or language is not installed. Choose another voice in Settings, under Read Aloud.")
                setState(TtsState.IDLE)
                return@TextToSpeech
            }
            val next = pendingStart
            pendingStart = null
            next?.invoke()
        }
    }

    private fun loadChapter(index: Int, startProgression: Double) {
        if (index == loadedLinkIndex && state == TtsState.PLAYING) {
            LibraryScanner.logTts(context, "chapter $index already active; reload ignored")
            return
        }
        job?.cancel()
        job = scope.launch {
            val link = publication.readingOrder[index]
            val resource = publication.get(link)
            val bytes = if (resource != null) {
                withContext(Dispatchers.IO) { resource.read().getOrNull() ?: ByteArray(0) }
            } else ByteArray(0)
            if (bytes.isEmpty()) {
                skipChapter(index)
                return@launch
            }
            val plain = htmlToText(String(bytes, Charsets.UTF_8))
            if (plain.isBlank()) {
                skipChapter(index)
                return@launch
            }
            skipGuard = 0
            linkIndex = index
            loadedLinkIndex = index
            text = plain
            chunks = split(plain)
            LibraryScanner.logTts(context, "chapter $index loaded (${plain.length} chars, ${chunks.size} parts)")
            base = base?.copy(
                href = link.href.resolve(),
                title = link.title,
                locations = Locator.Locations(progression = startProgression)
            )
            val startOffset = (startProgression * plain.length).toInt().coerceIn(0, plain.length)
            chunkIndex = chunks.indexOfFirst { it.end > startOffset }.let { if (it < 0) chunks.lastIndex else it }
            lastProgress = -1.0
            speakCurrent()
        }
    }

    private fun skipChapter(index: Int) {
        skipGuard++
        if (skipGuard > publication.readingOrder.size || index + 1 >= publication.readingOrder.size) {
            stop()
            return
        }
        linkIndex = index + 1
        onChapterChanged(publication.readingOrder[linkIndex])
        loadChapter(linkIndex, 0.0)
    }

    private fun advanceChapter() {
        if (linkIndex + 1 >= publication.readingOrder.size) {
            stop()
            return
        }
        linkIndex++
        LibraryScanner.logTts(context, "advance to chapter $linkIndex")
        onChapterChanged(publication.readingOrder[linkIndex])
        loadChapter(linkIndex, 0.0)
    }

    private fun speakCurrent() {
        val instance = engine
        if (instance == null || !ready) return
        if (chunkIndex !in chunks.indices) {
            advanceChapter()
            return
        }
        if (!requestFocus()) {
            onError("Another app is using audio. Close it and try again.")
            stop()
            return
        }
        val chunk = chunks[chunkIndex]
        val key = "$linkIndex:$chunkIndex"
        val now = SystemClock.elapsedRealtime()
        if (key == lastSpeakKey && now - lastSpeakAt < 3000) {
            LibraryScanner.logTts(context, "skip duplicate $key")
            return
        }
        lastSpeakKey = key
        lastSpeakAt = now
        currentUtterance = "tts_${++utteranceSeq}"
        LibraryScanner.logTts(context, "speak $key ${chunk.text.take(60)}")
        instance.speak(chunk.text, TextToSpeech.QUEUE_ADD, null, currentUtterance)
        setState(TtsState.PLAYING)
        reportProgress(chunk.start)
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            if (utteranceId != currentUtterance) return
            LibraryScanner.logTts(context, "done $utteranceId")
            // Consume it before the async work: engines occasionally deliver onDone twice,
            // and a duplicate must not advance the text a second time.
            currentUtterance = ""
            scope.launch(Dispatchers.Main) {
                if (state != TtsState.PLAYING) return@launch
                chunkIndex++
                if (chunkIndex >= chunks.size) advanceChapter() else speakCurrent()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            failSpeech()
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            failSpeech()
        }
    }

    private fun failSpeech() {
        scope.launch(Dispatchers.Main) {
            if (state == TtsState.IDLE) return@launch
            onError("Speech stopped unexpectedly for this passage.")
            stop()
        }
    }

    private fun reportProgress(offset: Int) {
        val locator = base ?: return
        if (text.isEmpty()) return
        val progression = (offset.toDouble() / text.length).coerceIn(0.0, 1.0)
        if (lastProgress >= 0 && abs(progression - lastProgress) < 0.04) return
        lastProgress = progression
        // Only the in-chapter position: a guessed book-wide percentage can make
        // Readium jump far ahead of the voice.
        onNavigate(
            locator.copy(
                locations = Locator.Locations(progression = progression)
            )
        )
    }

    private fun setState(newState: TtsState) {
        if (state == newState) return
        state = newState
        onStateChanged(newState)
    }

    private fun requestFocus(): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        scope.launch(Dispatchers.Main) { pause() }
                    }
                }
                .build()
            focusRequest = request
            audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(
                { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        scope.launch(Dispatchers.Main) { pause() }
                    }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(null)
        }
        focusRequest = null
    }

    private fun htmlToText(html: String): String {
        val cleaned = html
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<(br|/p|/div|/h[1-6]|/li|/tr|/section|/article)[^>]*>"), "\n")
        val plain = Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY).toString()
        return plain
            .replace(Regex("[\\t\\u00A0 ]+"), " ")
            .replace(Regex("\\n\\s*\\n+"), "\n\n")
            .trim()
    }

    private fun split(plain: String): List<Chunk> {
        val out = mutableListOf<Chunk>()
        var start = 0
        var i = 0
        while (i < plain.length) {
            val c = plain[i]
            val boundary = c == '\n' ||
                ((c == '.' || c == '!' || c == '?' || c == '…') &&
                    (i + 1 >= plain.length || plain[i + 1].isWhitespace()))
            val tooLong = i - start >= 300
            if (boundary || tooLong) {
                addChunk(out, plain, start, i + 1)
                start = i + 1
            }
            i++
        }
        if (start < plain.length) addChunk(out, plain, start, plain.length)
        return out
    }

    private fun addChunk(out: MutableList<Chunk>, plain: String, from: Int, to: Int) {
        val piece = plain.substring(from, to)
        val lead = piece.indexOfFirst { !it.isWhitespace() }
        if (lead < 0) return
        val trimmed = piece.trim()
        if (trimmed.isEmpty()) return
        out.add(Chunk(trimmed, from + lead, to))
    }
}
