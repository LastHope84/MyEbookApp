package com.example.myebookapp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reads PDF page text or a selection aloud. Sentences are reported with their
 * offsets in the page text so the reader can highlight the current line.
 */
class PdfSpeech(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onState: (TtsState) -> Unit,
    private val onSentence: (start: Int, end: Int, text: String) -> Unit,
    private val onFinished: () -> Unit,
    private val onError: (String) -> Unit
) {
    private data class Segment(val text: String, val start: Int, val end: Int)

    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: (() -> Unit)? = null
    private var segments: List<Segment> = emptyList()
    private var index = 0
    private var state = TtsState.IDLE
    private var currentUtterance = ""
    private var sequence = 0
    private var focusRequest: AudioFocusRequest? = null

    val currentState: TtsState get() = state

    fun speak(text: String, baseOffset: Int = 0) {
        if (text.isBlank()) return
        ensureEngine {
            segments = split(text, baseOffset)
            index = 0
            requestFocus()
            setState(TtsState.PLAYING)
            speakCurrent()
        }
    }

    fun pause() {
        if (state != TtsState.PLAYING) return
        engine?.stop()
        currentUtterance = ""
        onSentence(-1, -1, "")
        abandonFocus()
        setState(TtsState.PAUSED)
    }

    fun resume() {
        if (state != TtsState.PAUSED) return
        requestFocus()
        setState(TtsState.PLAYING)
        speakCurrent()
    }

    fun stop() {
        engine?.stop()
        currentUtterance = ""
        segments = emptyList()
        index = 0
        onSentence(-1, -1, "")
        abandonFocus()
        setState(TtsState.IDLE)
    }

    fun release() {
        stop()
        engine?.shutdown()
        engine = null
        ready = false
        pending = null
    }

    private fun ensureEngine(then: () -> Unit) {
        val existing = engine
        if (existing != null && ready) {
            then()
            return
        }
        pending = then
        if (existing != null) return
        val created = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                runCatching { TtsPrefs.apply(engine, context) }
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == null || utteranceId != currentUtterance) return
                        scope.launch(Dispatchers.Main) { advance() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        fail("The speech engine could not read this page.")
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        fail("The speech engine could not read this page.")
                    }
                })
                scope.launch(Dispatchers.Main) {
                    val action = pending
                    pending = null
                    action?.invoke()
                }
            } else {
                scope.launch(Dispatchers.Main) {
                    pending = null
                    onError("Text-to-speech is not available on this phone.")
                }
            }
        }
        engine = created
    }

    private fun speakCurrent() {
        val current = segments.getOrNull(index)
        if (current == null) {
            stop()
            return
        }
        val utterance = "pdf-${sequence++}"
        currentUtterance = utterance
        onSentence(current.start, current.end, current.text)
        val result = engine?.speak(current.text, TextToSpeech.QUEUE_FLUSH, null, utterance)
        if (result == TextToSpeech.ERROR) fail("The speech engine could not read this page.")
    }

    private fun advance() {
        if (state != TtsState.PLAYING) return
        index++
        if (index >= segments.size) {
            currentUtterance = ""
            onSentence(-1, -1, "")
            // Keep PLAYING: the reader decides whether to advance the page.
            onFinished()
        } else {
            speakCurrent()
        }
    }

    private fun fail(message: String) {
        scope.launch(Dispatchers.Main) {
            stop()
            onError(message)
        }
    }

    private fun setState(newState: TtsState) {
        if (state == newState) return
        state = newState
        onState(newState)
    }

    private fun split(text: String, base: Int): List<Segment> {
        val out = mutableListOf<Segment>()

        fun add(from: Int, to: Int) {
            if (to <= from) return
            val raw = text.substring(from, to)
            val lead = raw.indexOfFirst { !it.isWhitespace() }
            if (lead < 0) return
            var start = from + lead
            var end = from + raw.trimEnd().length
            while (end - start > 320) {
                var cut = text.lastIndexOf(' ', start + 300)
                if (cut <= start) cut = start + 300
                out.add(Segment(text.substring(start, cut).trim(), base + start, base + cut))
                start = cut
                while (start < end && text[start].isWhitespace()) start++
            }
            if (end > start) out.add(Segment(text.substring(start, end), base + start, base + end))
        }

        var start = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '.' || ch == '!' || ch == '?' || ch == '…' || ch == '\n') {
                var end = i + 1
                while (end < text.length &&
                    (text[end] == '"' || text[end] == ')' || text[end] == '\'' || text[end] == ']')
                ) end++
                add(start, end)
                start = end
                i = end
            } else {
                i++
            }
        }
        if (start < text.length) add(start, text.length)
        if (out.isEmpty() && text.isNotBlank()) out.add(Segment(text.trim(), base, base + text.length))
        return out
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
            runCatching { audio.requestAudioFocus(request) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audio.requestAudioFocus(
                    { change ->
                        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            scope.launch(Dispatchers.Main) { pause() }
                        }
                    },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        }
    }

    private fun abandonFocus() {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audio.abandonAudioFocus(null) }
        }
        focusRequest = null
    }
}
