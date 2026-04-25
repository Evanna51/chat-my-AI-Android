package com.example.aichat

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bytedance.speech.speechengine.SpeechEngine
import com.bytedance.speech.speechengine.SpeechEngineDefines
import com.bytedance.speech.speechengine.SpeechEngineGenerator
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object VolcEngineTTSManager {

    private const val TAG = "VolcEngineTTS"

    enum class State { IDLE, LOADING, PLAYING, PAUSED }

    interface TTSCallback {
        fun onStateChanged(state: State)
        fun onError(message: String)
    }

    private var engine: SpeechEngine? = null
    private var initialized = false
    private var appContext: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var state: State = State.IDLE

    @Volatile
    private var currentCallback: TTSCallback? = null

    @Volatile
    private var currentMessageId: Long? = null

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        try {
            val app = context.applicationContext as Application
            SpeechEngineGenerator.PrepareEnvironment(context.applicationContext, app)
            initialized = true
            Log.d(TAG, "TTS environment prepared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare TTS environment", e)
        }
    }

    fun speak(
        text: String,
        messageId: Long,
        callback: TTSCallback,
        params: VolcEngineHttpTTS.SpeechParams? = null,
    ) {
        val ctx = appContext ?: run {
            callback.onError("TTS not initialized")
            return
        }
        val config = TTSConfigStore(ctx)
        if (!config.isEnabled()) {
            callback.onError("TTS is disabled")
            return
        }

        // Dispatch to HTTP API mode if configured
        if (config.isHttpApiMode()) {
            VolcEngineHttpTTS.speak(text, messageId, config, callback, params)
            return
        }

        val appId = config.getAppId()
        val token = config.getAccessToken()
        if (appId.isBlank() || token.isBlank()) {
            callback.onError("Please configure TTS App ID and Token in Settings")
            return
        }

        executor.execute {
            try {
                stopInternal()

                currentCallback = callback
                currentMessageId = messageId
                updateState(State.LOADING)

                val eng = SpeechEngineGenerator.getInstance()
                engine = eng
                eng.createEngine()

                // Engine type
                eng.setOptionString(
                    SpeechEngineDefines.PARAMS_KEY_ENGINE_NAME_STRING,
                    SpeechEngineDefines.TTS_ENGINE
                )

                // Auth
                eng.setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_ID_STRING, appId)
                eng.setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_TOKEN_STRING, token)
                eng.setOptionString(SpeechEngineDefines.PARAMS_KEY_UID_STRING, "android_user")

                // TTS params
                eng.setOptionString(
                    SpeechEngineDefines.PARAMS_KEY_TTS_CLUSTER_STRING,
                    config.getCluster()
                )
                eng.setOptionString(
                    SpeechEngineDefines.PARAMS_KEY_TTS_VOICE_TYPE_ONLINE_STRING,
                    config.getVoiceType()
                )

                // Speed & volume
                eng.setOptionDouble(
                    SpeechEngineDefines.PARAMS_KEY_TTS_SPEED_RATIO_DOUBLE,
                    config.getSpeedRatio().toDouble()
                )
                eng.setOptionDouble(
                    SpeechEngineDefines.PARAMS_KEY_TTS_VOLUME_RATIO_DOUBLE,
                    config.getVolumeRatio().toDouble()
                )

                // Logging
                eng.setOptionString(
                    SpeechEngineDefines.PARAMS_KEY_LOG_LEVEL_STRING,
                    SpeechEngineDefines.LOG_LEVEL_WARN
                )

                // Text to synthesize
                eng.setOptionString(
                    SpeechEngineDefines.PARAMS_KEY_TTS_TEXT_STRING,
                    text
                )

                // Listener
                eng.setListener(SpeechEngine.SpeechListener { type, data, len ->
                    handleMessage(type, data, len)
                })

                // Init engine
                val ret = eng.initEngine()
                if (ret != SpeechEngineDefines.ERR_NO_ERROR) {
                    Log.e(TAG, "initEngine failed: $ret")
                    updateState(State.IDLE)
                    notifyError("TTS engine init failed: $ret")
                    return@execute
                }

                // Start engine
                eng.sendDirective(SpeechEngineDefines.DIRECTIVE_START_ENGINE, "")

            } catch (e: Exception) {
                Log.e(TAG, "speak() error", e)
                updateState(State.IDLE)
                notifyError("TTS error: ${e.message}")
            }
        }
    }

    private fun handleMessage(type: Int, data: ByteArray?, len: Int) {
        when (type) {
            SpeechEngineDefines.MESSAGE_TYPE_ENGINE_START -> {
                Log.d(TAG, "Engine started, sending synthesis directive")
                updateState(State.PLAYING)
                executor.execute {
                    try {
                        engine?.sendDirective(SpeechEngineDefines.DIRECTIVE_SYNTHESIS, "")
                    } catch (e: Exception) {
                        Log.e(TAG, "sendDirective SYNTHESIS failed", e)
                    }
                }
            }
            SpeechEngineDefines.MESSAGE_TYPE_TTS_START_PLAYING -> {
                Log.d(TAG, "TTS playback started")
                updateState(State.PLAYING)
            }
            SpeechEngineDefines.MESSAGE_TYPE_TTS_FINISH_PLAYING -> {
                Log.d(TAG, "TTS playback finished")
                updateState(State.IDLE)
                currentMessageId = null
            }
            SpeechEngineDefines.MESSAGE_TYPE_ENGINE_STOP -> {
                Log.d(TAG, "Engine stopped")
                if (state != State.IDLE) {
                    updateState(State.IDLE)
                    currentMessageId = null
                }
            }
            SpeechEngineDefines.MESSAGE_TYPE_ENGINE_ERROR -> {
                val msg = if (data != null && len > 0) {
                    String(data, 0, len, Charsets.UTF_8)
                } else {
                    "Unknown error"
                }
                Log.e(TAG, "Engine error: $msg")
                updateState(State.IDLE)
                currentMessageId = null
                notifyError("TTS error: $msg")
            }
        }
    }

    fun pause() {
        if (VolcEngineHttpTTS.getState() != State.IDLE) {
            VolcEngineHttpTTS.pause()
            return
        }
        if (state != State.PLAYING) return
        executor.execute {
            try {
                engine?.sendDirective(SpeechEngineDefines.DIRECTIVE_PAUSE_PLAYER, "")
                updateState(State.PAUSED)
            } catch (e: Exception) {
                Log.e(TAG, "pause() error", e)
            }
        }
    }

    fun resume() {
        if (VolcEngineHttpTTS.getState() == State.PAUSED) {
            VolcEngineHttpTTS.resume()
            return
        }
        if (state != State.PAUSED) return
        executor.execute {
            try {
                engine?.sendDirective(SpeechEngineDefines.DIRECTIVE_RESUME_PLAYER, "")
                updateState(State.PLAYING)
            } catch (e: Exception) {
                Log.e(TAG, "resume() error", e)
            }
        }
    }

    fun stop() {
        VolcEngineHttpTTS.stop()
        executor.execute { stopInternal() }
    }

    private fun stopInternal() {
        try {
            val eng = engine ?: return
            eng.sendDirective(SpeechEngineDefines.DIRECTIVE_SYNC_STOP_ENGINE, "")
            eng.destroyEngine()
        } catch (e: Exception) {
            Log.e(TAG, "stopInternal error", e)
        } finally {
            engine = null
            currentCallback = null
            currentMessageId = null
            state = State.IDLE
        }
    }

    fun release() {
        executor.execute {
            stopInternal()
            initialized = false
        }
    }

    fun isPlaying(): Boolean =
        state == State.PLAYING || state == State.LOADING ||
        VolcEngineHttpTTS.isPlaying()

    fun currentPlayingMessageId(): Long? =
        VolcEngineHttpTTS.currentPlayingMessageId() ?: currentMessageId

    fun getState(): State = state

    private fun updateState(newState: State) {
        state = newState
        val cb = currentCallback
        mainHandler.post { cb?.onStateChanged(newState) }
    }

    private fun notifyError(message: String) {
        val cb = currentCallback
        mainHandler.post { cb?.onError(message) }
    }
}
