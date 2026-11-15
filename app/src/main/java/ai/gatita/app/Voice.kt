package ai.gatita.app

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    /** 음성 인식 중 마이크 소리 크기 (화면 파형용) */
    var onRms: ((Float) -> Unit)? = null
    @Volatile private var lastText: String? = null

    // Pleos TTS는 새 문장을 받으면 재생 중인 문장을 끊어 버립니다(끝부분이 잘리거나 '딱' 소리).
    // 그래서 문장을 줄 세워 두고, 앞 문장이 끝났다는 onDone을 받은 뒤 다음 문장을 보냅니다.
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private val lock = Any()
    private var speaking = false
    private var currentDone = true
    private var afterSpeech: (() -> Unit)? = null
    private val watchdog = Runnable { onUtteranceEnd("timeout") }

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
                override fun onDone() = onUtteranceEnd("done")
                override fun onStop() = onUtteranceEnd("stop")
                override fun onUpdatedRms(rms: Double) {}
                override fun onError(error: TtsError) {
                    onLog("Pleos TTS 재생 오류($error) → 기본 TTS")
                    lastText?.let { fallbackSay(it) }
                    onUtteranceEnd("error")
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
                override fun onUpdatedRms(rms: Double) { onRms?.invoke(rms.toFloat()) }
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

    /**
     * 문장을 말합니다. 앞 문장이 재생 중이면 끝난 뒤 이어서 말합니다.
     * urgent = true(하차 경고 등)면 줄을 비우고 바로 끼어듭니다.
     */
    fun say(text: String, urgent: Boolean = false) {
        main.post {
            synchronized(lock) {
                if (urgent) { queue.clear(); speaking = false; currentDone = true }
                queue.addLast(text)
            }
            if (urgent) runCatching { pleosTts?.stop() }
            speakNext()
        }
    }

    private fun speakNext() {
        val text = synchronized(lock) {
            if (speaking) return
            val t = queue.removeFirstOrNull()
            if (t == null) { val cb = afterSpeech; afterSpeech = null; main.post { cb?.invoke() }; return }
            speaking = true; currentDone = false
            t
        }
        onLog("🔊 $text")
        lastText = text
        // onDone이 오지 않는 경우를 대비: 한국어 낭독 속도(약 6자/초)로 어림한 시간 + 여유
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 3000L + text.length * 250L)
        val p = pleosTts
        if (p != null && pleosTtsReady) {
            runCatching { p.speak(text, true) }.onFailure { startFallback(); fallbackSay(text) }
        } else fallbackSay(text)
    }

    /** 한 문장 재생이 끝났을 때. 같은 문장에 onDone이 두 번 올 수 있어 한 번만 처리합니다 */
    private fun onUtteranceEnd(why: String) {
        synchronized(lock) {
            if (!speaking || currentDone) return
            currentDone = true; speaking = false
        }
        main.removeCallbacks(watchdog)
        main.post { speakNext() }
    }

    private fun fallbackSay(text: String) {
        startFallback()
        fallbackTts?.speak(text, AndroidTts.QUEUE_ADD, null, "gatita")
        // 기본 TTS는 onDone을 따로 받지 않으므로 watchdog 시간이 지나면 다음 문장으로 넘어갑니다
    }

    /** 지금 말하는 문장과 대기 중인 문장을 모두 마친 뒤 실행 (말이 끝난 뒤 듣기 시작할 때) */
    fun whenQuiet(action: () -> Unit) {
        main.post {
            val idle = synchronized(lock) { !speaking && queue.isEmpty() }
            if (idle) action() else afterSpeech = action
        }
    }

    /** 음성 인식을 시작하고, 최종 결과를 콜백으로 전달 */
    fun listen(callback: (String) -> Unit) {
        onHeard = callback
        // 앱이 말하는 중에 마이크를 열면 자기 목소리를 듣거나 재생이 흔들릴 수 있어, 말이 끝난 뒤 듣습니다
        whenQuiet {
            if (sttReady) runCatching { stt?.request() }.onFailure { onLog("STT 요청 실패: ${it.message}") }
        }
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        runCatching { pleosTts?.release() }
        runCatching { stt?.release() }
        fallbackTts?.shutdown()
    }
}
