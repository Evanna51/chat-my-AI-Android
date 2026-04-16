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
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object VolcEngineHttpTTS {

    private const val TAG = "VolcEngineHttpTTS"
    private const val API_URL_V3 = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"

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
            .readTimeout(60, TimeUnit.SECONDS)
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

                val encoding = config.getEncoding()
                val requestJson = JSONObject().apply {
                    put("user", JSONObject().apply {
                        put("uid", "android_user")
                    })
                    put("req_params", JSONObject().apply {
                        put("text", text)
                        put("speaker", config.getVoiceType())
                        put("audio_params", JSONObject().apply {
                            put("format", encoding)
                            put("sample_rate", 24000)
                        })
                    })
                }

                val body = requestJson.toString()
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(API_URL_V3)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-Api-Key", apiKey)
                    .addHeader("X-Api-Resource-Id", config.getResourceId())
                    .addHeader("X-Api-Request-Id", UUID.randomUUID().toString())
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "HTTP ${response.code}: $errorBody")
                    updateState(VolcEngineTTSManager.State.IDLE)
                    notifyError("TTS HTTP error ${response.code}")
                    return@execute
                }

                val audioBuffer = ByteArrayOutputStream()
                val inputStream = response.body?.byteStream()
                if (inputStream == null) {
                    updateState(VolcEngineTTSManager.State.IDLE)
                    notifyError("Empty response from TTS server")
                    return@execute
                }

                val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line?.trim() ?: continue
                    if (trimmed.isEmpty()) continue

                    try {
                        val chunk = JSONObject(trimmed)
                        val code = chunk.optInt("code", -1)
                        if (code != 3000) {
                            val message = chunk.optString("message", "Unknown error")
                            Log.e(TAG, "TTS chunk error: code=$code, message=$message")
                            updateState(VolcEngineTTSManager.State.IDLE)
                            notifyError("TTS error ($code): $message")
                            reader.close()
                            return@execute
                        }

                        val audioData = chunk.optString("data", "")
                        if (audioData.isNotBlank()) {
                            val decoded = Base64.decode(audioData, Base64.DEFAULT)
                            audioBuffer.write(decoded)
                        }

                        val sequence = chunk.optInt("sequence", 0)
                        if (sequence == -1) break
                    } catch (e: Exception) {
                        Log.w(TAG, "Skip non-JSON line: $trimmed")
                    }
                }
                reader.close()

                val allAudioBytes = audioBuffer.toByteArray()
                if (allAudioBytes.isEmpty()) {
                    updateState(VolcEngineTTSManager.State.IDLE)
                    notifyError("No audio data in response")
                    return@execute
                }

                playAudio(allAudioBytes, encoding)

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
