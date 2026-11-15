package ai.gatita.app

import android.content.Context
import java.util.Locale
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.speech.tts.TextToSpeech as AndroidTts
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode

/**
 * 음성 입출력. Pleos TextToSpeech / SpeechToText SDK(On-Device)를 우선 사용하고,
 * 초기화에 실패하면 TTS는 Android 기본 엔진으로 대체합니다. STT 실패 시 화면 버튼으로 응답할 수 있습니다.
 */
class Voice(private val context: Context, private val onLog: (String) -> Unit) {

    private var pleosTts: TextToSpeech? = null
    private var pleosTtsReady = false
    private var fallbackTts: AndroidTts? = null
    private var stt: SpeechToText? = null
    private var sttReady = false
    private var onHeard: ((String) -> Unit)? = null
    @Volatile private var lastText: String? = null

    fun start() {
        try {
            val tts = TextToSpeech(context, TtsMode.ON_DEVICE)
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() { tts.setLocale(Locale.KOREA); pleosTtsReady = true; onLog("Pleos TTS 준비") }
                override fun onFailure(error: TtsError) { onLog("Pleos TTS 실패($error) → 기본 TTS 사용"); startFallback() }
            })
            // 합성 중 오류가 나면 같은 문장을 Android 기본 TTS로 다시 읽습니다
            tts.addEventListener(object : EventListener {
                override fun onStart() {}
                override fun onReady() {}
                override fun onDone() {}
                override fun onStop() {}
                override fun onUpdatedRms(rms: Double) {}
                override fun onError(error: TtsError) {
                    onLog("Pleos TTS 재생 오류($error) → 기본 TTS")
                    lastText?.let { fallbackSay(it) }
                }
            })
            pleosTts = tts
        } catch (t: Throwable) { onLog("Pleos TTS 오류: ${t.message}"); startFallback() }

        try {
            val s = SpeechToText(context, SttMode.ON_DEVICE)
            s.initialize(object : SttInit {
                override fun onSuccess() { s.setLocale(Locale.KOREA); sttReady = true; onLog("Pleos STT 준비") }
                override fun onFailure(error: SttError) { onLog("Pleos STT 실패($error) → 화면 버튼 사용") }
            })
            s.addListener(object : ResultListener {
                override fun onUpdated(text: String, isFinal: Boolean) { if (isFinal) onHeard?.invoke(text) }
                override fun onUpdatedEpdData(start: Long, end: Long) {}
                override fun onStartedRecognition() {}
                override fun onEndedRecognition() {}
                override fun onError(error: SttError) { onLog("STT 오류: $error") }
                override fun onReady() {}
                override fun onUpdatedRms(rms: Double) {}
            })
            stt = s
        } catch (t: Throwable) { onLog("Pleos STT 오류: ${t.message}") }
    }

    private fun startFallback() {
        if (fallbackTts != null) return
        fallbackTts = AndroidTts(context) { status ->
            if (status == AndroidTts.SUCCESS) fallbackTts?.language = Locale.KOREA
        }
    }

    fun say(text: String) {
        onLog("🔊 $text")
        lastText = text
        val p = pleosTts
        if (p != null && pleosTtsReady) {
            runCatching { p.speak(text, true) }.onFailure { startFallback(); fallbackSay(text) }
        } else fallbackSay(text)
    }

    private fun fallbackSay(text: String) {
        startFallback()
        fallbackTts?.speak(text, AndroidTts.QUEUE_FLUSH, null, "gatita")
    }

    /** 음성 인식을 시작하고, 최종 결과를 콜백으로 전달 */
    fun listen(callback: (String) -> Unit) {
        onHeard = callback
        if (sttReady) runCatching { stt?.request() }.onFailure { onLog("STT 요청 실패: ${it.message}") }
    }

    fun release() {
        runCatching { pleosTts?.release() }
        runCatching { stt?.release() }
        fallbackTts?.shutdown()
    }
}
