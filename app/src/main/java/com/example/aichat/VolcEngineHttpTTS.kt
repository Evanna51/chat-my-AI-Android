package com.example.aichat

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object VolcEngineHttpTTS {

    private const val TAG = "VolcEngineHttpTTS"
    private const val API_URL = "https://openspeech.bytedance.com/api/v1/tts"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    private var state: VolcEngineTTSManager.State = VolcEngineTTSManager.State.IDLE

    @Volatile
    private var currentCallback: VolcEngineTTSManager.TTSCallback? = null

    @Volatile
    private var currentMessageId: Long? = null

    private var cacheDir: File? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun init(cacheDir: File) {
        this.cacheDir = cacheDir
    }

    fun speak(text: String, messageId: Long, config: TTSConfigStore, callback: VolcEngineTTSManager.TTSCallback) {
        val apiKey = config.getApiKey()
        if (apiKey.isBlank()) {
            callback.onError("请在设置中配置 API Key")
            return
        }

        executor.execute {
            try {
                stopInternal()

                currentCallback = callback
                currentMessageId = messageId
                updateState(VolcEngineTTSManager.State.LOADING)

                val reqId = UUID.randomUUID().toString().replace("-", "")
                val requestJson = JSONObject().apply {
                    put("app", JSONObject().apply {
                        put("cluster", config.getCluster())
                    })
                    put("user", JSONObject().apply {
                        put("uid", "android_user")
                    })
                    put("audio", JSONObject().apply {
                        put("voice_type", config.getVoiceType())
                        put("encoding", config.getEncoding())
                        put("speed_ratio", config.getSpeedRatio().toDouble())
                        put("volume_ratio", config.getVolumeRatio().toDouble())
                    })
                    put("request", JSONObject().apply {
                        put("reqid", reqId)
                        put("text", text)
                        put("operation", "query")
                    })
                }

                val body = requestJson.toString()
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(API_URL)
                    .addHeader("x-api-key", apiKey)
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (responseBody == null) {
                    updateState(VolcEngineTTSManager.State.IDLE)
                    notifyError("Empty response from TTS server")
                    return@execute
                }

                val json = JSONObject(responseBody)
                val code = json.optInt("code", -1)
                if (code != 3000) {
                    val message = json.optString("message", "Unknown error")
                    Log.e(TAG, "TTS API error: code=$code, message=$message")
                    updateState(VolcEngineTTSManager.State.IDLE)
                    notifyError("TTS error ($code): $message")
                    return@execute
                }

                val audioData = json.optString("data", "")
                if (audioData.isBlank()) {
                    updateState(VolcEngineTTSManager.State.IDLE)
                    notifyError("No audio data in response")
                    return@execute
                }

                val audioBytes = Base64.decode(audioData, Base64.DEFAULT)
                playAudio(audioBytes, config.getEncoding())

            } catch (e: Exception) {
                Log.e(TAG, "speak() error", e)
                updateState(VolcEngineTTSManager.State.IDLE)
                notifyError("TTS error: ${e.message}")
            }
        }
    }

    private fun playAudio(audioBytes: ByteArray, encoding: String) {
        val suffix = when (encoding) {
            "mp3" -> ".mp3"
            "ogg_opus" -> ".ogg"
            "wav" -> ".wav"
            else -> ".pcm"
        }
        val tempFile = File(cacheDir, "tts_audio$suffix")
        FileOutputStream(tempFile).use { it.write(audioBytes) }

        mainHandler.post {
            try {
                releasePlayer()
                val player = MediaPlayer()
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                player.setDataSource(tempFile.absolutePath)
                player.setOnPreparedListener {
                    updateState(VolcEngineTTSManager.State.PLAYING)
                    it.start()
                }
                player.setOnCompletionListener {
                    updateState(VolcEngineTTSManager.State.IDLE)
                    currentMessageId = null
                    releasePlayer()
                    tempFile.delete()
                }
                player.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    updateState(VolcEngineTTSManager.State.IDLE)
                    currentMessageId = null
                    notifyError("Audio playback error")
                    releasePlayer()
                    tempFile.delete()
                    true
                }
                mediaPlayer = player
                player.prepareAsync()
            } catch (e: Exception) {
                Log.e(TAG, "playAudio error", e)
                updateState(VolcEngineTTSManager.State.IDLE)
                notifyError("Audio playback error: ${e.message}")
            }
        }
    }

    fun pause() {
        mainHandler.post {
            try {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        it.pause()
                        updateState(VolcEngineTTSManager.State.PAUSED)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "pause error", e)
            }
        }
    }

    fun resume() {
        mainHandler.post {
            try {
                mediaPlayer?.let {
                    it.start()
                    updateState(VolcEngineTTSManager.State.PLAYING)
                }
            } catch (e: Exception) {
                Log.e(TAG, "resume error", e)
            }
        }
    }

    fun stop() {
        executor.execute { stopInternal() }
    }

    private fun stopInternal() {
        mainHandler.post { releasePlayer() }
        currentCallback = null
        currentMessageId = null
        state = VolcEngineTTSManager.State.IDLE
    }

    private fun releasePlayer() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    fun isPlaying(): Boolean = state == VolcEngineTTSManager.State.PLAYING ||
            state == VolcEngineTTSManager.State.LOADING

    fun currentPlayingMessageId(): Long? = currentMessageId

    fun getState(): VolcEngineTTSManager.State = state

    private fun updateState(newState: VolcEngineTTSManager.State) {
        state = newState
        val cb = currentCallback
        mainHandler.post { cb?.onStateChanged(newState) }
    }

    private fun notifyError(message: String) {
        val cb = currentCallback
        mainHandler.post { cb?.onError(message) }
    }
}
