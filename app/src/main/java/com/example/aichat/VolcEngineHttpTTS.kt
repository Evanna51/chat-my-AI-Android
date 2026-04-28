package com.example.aichat

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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

    private const val PCM_SAMPLE_RATE = 24000

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var cancelRequested: Boolean = false

    @Volatile
    private var currentCall: okhttp3.Call? = null

    @Volatile
    private var pcmFramesWritten: Long = 0L

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

    data class SpeechParams(
        val emotion: String? = null,
        val emotionScale: Int? = null,
        val speechRate: Int? = null,
        val loudnessRate: Int? = null,
        val pitchRate: Int? = null,
    )

    fun speak(
        text: String,
        messageId: Long,
        config: TTSConfigStore,
        callback: VolcEngineTTSManager.TTSCallback,
        params: SpeechParams? = null,
    ) {
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
                val audioParams = JSONObject().apply {
                    put("format", encoding)
                    put("sample_rate", 24000)
                }
                // emotion / emotion_scale / speech_rate / loudness_rate / pitch_rate 必须放在
                // req_params 顶层（与 text、speaker 同级），塞进 audio_params 服务端不识别。
                val requestJson = JSONObject().apply {
                    put("user", JSONObject().apply {
                        put("uid", "android_user")
                    })
                    put("req_params", JSONObject().apply {
                        put("text", text)
                        put("speaker", config.getVoiceType())
                        put("audio_params", audioParams)
                        params?.emotion?.let { put("emotion", it) }
                        params?.emotionScale?.let { put("emotion_scale", it) }
                        params?.speechRate?.let { put("speech_rate", it) }
                        params?.loudnessRate?.let { put("loudness_rate", it) }
                        params?.pitchRate?.let { put("pitch_rate", it) }
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

                val call = client.newCall(request)
                currentCall = call
                val response = call.execute()

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "HTTP ${response.code}: $errorBody")
                    updateState(VolcEngineTTSManager.State.IDLE)
                    notifyError("TTS HTTP error ${response.code}")
                    return@execute
                }

                val inputStream = response.body?.byteStream()
                if (inputStream == null) {
                    updateState(VolcEngineTTSManager.State.IDLE)
                    notifyError("Empty response from TTS server")
                    return@execute
                }

                val streaming = encoding == "pcm"
                val audioBuffer = if (streaming) null else ByteArrayOutputStream()
                cancelRequested = false
                pcmFramesWritten = 0L

                val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                var line: String?
                var receivedAnyAudio = false
                streamLoop@ while (reader.readLine().also { line = it } != null) {
                    if (cancelRequested) break
                    val trimmed = line?.trim() ?: continue
                    if (trimmed.isEmpty()) continue

                    try {
                        val chunk = JSONObject(trimmed)
                        Log.d(TAG, "chunk: $trimmed")

                        // Only treat as error if code is present and not 3000/0
                        if (chunk.has("code")) {
                            val code = chunk.getInt("code")
                            if (code == 20000000) break@streamLoop
                            if (code != 3000 && code != 0) {
                                val message = chunk.optString("message", "Unknown error")
                                Log.e(TAG, "TTS chunk error: code=$code, message=$message")
                                updateState(VolcEngineTTSManager.State.IDLE)
                                notifyError("TTS error ($code): $message")
                                reader.close()
                                releaseAudioTrack()
                                return@execute
                            }
                        }

                        val audioData = chunk.optString("data", "")
                        if (audioData.isNotBlank()) {
                            val decoded = Base64.decode(audioData, Base64.DEFAULT)
                            if (decoded.isNotEmpty()) {
                                receivedAnyAudio = true
                                if (streaming) {
                                    writePcmChunk(decoded)
                                } else {
                                    audioBuffer!!.write(decoded)
                                }
                            }
                        }

                        val sequence = chunk.optInt("sequence", 0)
                        if (sequence == -1) break@streamLoop
                    } catch (e: Exception) {
                        Log.w(TAG, "Skip non-JSON line: $trimmed")
                    }
                }
                reader.close()

                currentCall = null

                if (cancelRequested) {
                    releaseAudioTrack()
                    return@execute
                }

                if (streaming) {
                    finishPcmPlayback(receivedAnyAudio)
                } else {
                    val allAudioBytes = audioBuffer!!.toByteArray()
                    if (allAudioBytes.isEmpty()) {
                        updateState(VolcEngineTTSManager.State.IDLE)
                        notifyError("No audio data in response")
                        return@execute
                    }
                    playAudio(allAudioBytes, encoding)
                }

            } catch (e: Exception) {
                if (cancelRequested) {
                    Log.d(TAG, "speak() cancelled")
                } else {
                    Log.e(TAG, "speak() error", e)
                    updateState(VolcEngineTTSManager.State.IDLE)
                    notifyError("TTS error: ${e.message}")
                }
                currentCall = null
                releaseAudioTrack()
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

    /**
     * 写一段 PCM 数据到 AudioTrack；首次调用时懒创建并启动播放，状态切到 PLAYING。
     */
    private fun writePcmChunk(decoded: ByteArray) {
        var track = audioTrack
        if (track == null) {
            val minBuf = AudioTrack.getMinBufferSize(
                PCM_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferSize = if (minBuf > 0) minBuf * 2 else 9600
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(PCM_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack = track
            track.play()
            updateState(VolcEngineTTSManager.State.PLAYING)
        }
        try {
            // MODE_STREAM 下 write 会阻塞直到环形缓冲有空位，自然限速跟随播放节奏。
            val written = track.write(decoded, 0, decoded.size)
            if (written > 0) {
                // 16-bit mono → 2 bytes per frame
                pcmFramesWritten += (written / 2).toLong()
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack write error", e)
        }
    }

    /**
     * PCM 流接收完毕：等已写入样本播放到末尾再释放，避免末尾被切掉。
     */
    private fun finishPcmPlayback(receivedAnyAudio: Boolean) {
        if (!receivedAnyAudio) {
            updateState(VolcEngineTTSManager.State.IDLE)
            currentMessageId = null
            notifyError("No audio data in response")
            return
        }
        val track = audioTrack
        if (track == null) {
            updateState(VolcEngineTTSManager.State.IDLE)
            currentMessageId = null
            return
        }
        val targetFrames = pcmFramesWritten
        // 等播放头走到已写入末尾。安全上限 30s；cancelRequested 时立刻退出。
        var loops = 0
        while (!cancelRequested && loops < 1500) {
            try {
                val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                if (head >= targetFrames) break
                if (track.playState == AudioTrack.PLAYSTATE_STOPPED) break
            } catch (_: Exception) {
                break
            }
            try { Thread.sleep(20) } catch (_: InterruptedException) { break }
            loops++
        }
        try { track.stop() } catch (_: Exception) {}
        try { track.release() } catch (_: Exception) {}
        audioTrack = null
        updateState(VolcEngineTTSManager.State.IDLE)
        currentMessageId = null
    }

    private fun releaseAudioTrack() {
        val track = audioTrack ?: return
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
            track.flush()
            track.stop()
        } catch (_: Exception) {}
        try {
            track.release()
        } catch (_: Exception) {}
        audioTrack = null
    }

    fun pause() {
        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.pause()
                    updateState(VolcEngineTTSManager.State.PAUSED)
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "pause (track) error", e)
        }
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
        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PAUSED) {
                    it.play()
                    updateState(VolcEngineTTSManager.State.PLAYING)
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "resume (track) error", e)
        }
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
        cancelRequested = true
        executor.execute { stopInternal() }
    }

    private fun stopInternal() {
        cancelRequested = true
        try { currentCall?.cancel() } catch (_: Exception) {}
        currentCall = null
        releaseAudioTrack()
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
