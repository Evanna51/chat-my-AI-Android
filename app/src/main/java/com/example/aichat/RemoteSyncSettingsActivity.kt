package com.example.aichat

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.example.aichat.sync.ChatServerApi
import com.example.aichat.sync.DeviceIdProvider
import com.example.aichat.sync.HistoryBackfiller
import com.example.aichat.sync.RemoteSyncConfigStore
import com.example.aichat.sync.SnapshotUploader
import com.example.aichat.sync.SyncQueueDrainer
import com.example.aichat.sync.SyncScheduler
import com.example.aichat.sync.WsClient
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class RemoteSyncSettingsActivity : ThemedActivity() {

    private lateinit var store: RemoteSyncConfigStore
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var switchEnabled: MaterialSwitch
    private lateinit var switchSearchMemoryTool: MaterialSwitch
    private lateinit var editBaseUrl: TextInputEditText
    private lateinit var editApiKey: TextInputEditText
    private lateinit var textDeviceId: TextView
    private lateinit var textPending: TextView
    private lateinit var textLastAt: TextView
    private lateinit var textLastError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_sync_settings)
        store = RemoteSyncConfigStore(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        switchEnabled = findViewById(R.id.switchRemoteSyncEnabled)
        switchSearchMemoryTool = findViewById(R.id.switchSearchMemoryTool)
        editBaseUrl = findViewById(R.id.editRemoteSyncBaseUrl)
        editApiKey = findViewById(R.id.editRemoteSyncApiKey)
        textDeviceId = findViewById(R.id.textRemoteSyncDeviceId)
        textPending = findViewById(R.id.textRemoteSyncPending)
        textLastAt = findViewById(R.id.textRemoteSyncLastAt)
        textLastError = findViewById(R.id.textRemoteSyncLastError)

        switchEnabled.isChecked = store.isEnabled()
        switchSearchMemoryTool.isChecked = store.isSearchMemoryToolEnabled()
        editBaseUrl.setText(store.getBaseUrl())
        editApiKey.setText(store.getApiKey())
        textDeviceId.text = DeviceIdProvider.get(this)

        findViewById<MaterialButton>(R.id.btnRemoteSyncSave).setOnClickListener { saveAndApply() }
        findViewById<MaterialButton>(R.id.btnRemoteSyncTest).setOnClickListener { testConnection() }
        findViewById<MaterialButton>(R.id.btnRemoteSyncRunNow).setOnClickListener { runSyncNow() }
        findViewById<MaterialButton>(R.id.btnRemoteSyncBackfill).setOnClickListener { backfillHistory() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun saveAndApply() {
        val baseUrl = editBaseUrl.text?.toString().orEmpty()
        val apiKey = editApiKey.text?.toString().orEmpty()
        val enabled = switchEnabled.isChecked
        store.setBaseUrl(baseUrl)
        store.setApiKey(apiKey)
        store.setEnabled(enabled)
        store.setSearchMemoryToolEnabled(switchSearchMemoryTool.isChecked)
        if (enabled) {
            SyncScheduler.start(applicationContext)
            WsClient.start(applicationContext)
        } else {
            SyncScheduler.stop(applicationContext)
            WsClient.shutdown()
        }
        Toast.makeText(this, R.string.remote_sync_saved, Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun testConnection() {
        val baseUrl = editBaseUrl.text?.toString()?.trim()?.trimEnd('/').orEmpty()
        val apiKey = editApiKey.text?.toString()?.trim().orEmpty()
        if (baseUrl.isEmpty()) {
            Toast.makeText(this, R.string.remote_sync_base_url_required, Toast.LENGTH_SHORT).show()
            return
        }
        executor.execute {
            val ok = try {
                ChatServerApi(baseUrl, apiKey, timeoutSeconds = 5).health()
            } catch (_: Exception) { false }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) R.string.remote_sync_test_ok else R.string.remote_sync_test_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun runSyncNow() {
        if (!store.isReady()) {
            Toast.makeText(this, R.string.remote_sync_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.remote_sync_running, Toast.LENGTH_SHORT).show()
        executor.execute {
            val result = try {
                SyncQueueDrainer.drain(this)
            } catch (e: SyncQueueDrainer.SyncTransientException) {
                SyncQueueDrainer.Result.TransportError(e)
            } catch (e: Exception) {
                SyncQueueDrainer.Result.TransportError(e)
            }
            runOnUiThread {
                val msg = when (result) {
                    is SyncQueueDrainer.Result.Drained ->
                        getString(R.string.remote_sync_drained_summary,
                            result.accepted, result.skipped, result.rejected)
                    is SyncQueueDrainer.Result.Empty -> getString(R.string.remote_sync_no_pending)
                    is SyncQueueDrainer.Result.Disabled -> getString(R.string.remote_sync_not_ready)
                    is SyncQueueDrainer.Result.TransportError ->
                        getString(R.string.remote_sync_failed, result.cause.message ?: "")
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refreshStatus()
                // Schedule WorkManager periodic if user just enabled
                if (store.isEnabled()) SyncScheduler.start(applicationContext)
            }
        }
    }

    /**
     * 一次性同步: 先 stamp 历史消息进入待同步队列, 再走 /api/sync/snapshot 把
     * assistants 元数据 + 第一批 turns 推上去, 剩余 turns 通过普通 push 跟进.
     */
    private fun backfillHistory() {
        if (!store.isReady()) {
            Toast.makeText(this, R.string.remote_sync_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.remote_sync_backfill_running, Toast.LENGTH_SHORT).show()
        executor.execute {
            val backfill = try {
                HistoryBackfiller.backfill(this)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.remote_sync_failed, e.message ?: "backfill error"),
                        Toast.LENGTH_LONG).show()
                }
                return@execute
            }
            val upload = try {
                SnapshotUploader.upload(this)
            } catch (e: Exception) {
                SnapshotUploader.Result.TransportError(e)
            }
            runOnUiThread {
                val msg = when (upload) {
                    is SnapshotUploader.Result.Done ->
                        getString(R.string.remote_sync_snapshot_done,
                            backfill.stamped, upload.assistantsCount,
                            upload.accepted, upload.skipped, upload.rejected)
                    is SnapshotUploader.Result.Empty -> getString(R.string.remote_sync_backfill_none)
                    is SnapshotUploader.Result.Disabled -> getString(R.string.remote_sync_not_ready)
                    is SnapshotUploader.Result.TransportError ->
                        getString(R.string.remote_sync_failed, upload.cause.message ?: "")
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refreshStatus()
            }
        }
    }

    private fun refreshStatus() {
        executor.execute {
            val pending = AppDatabase.getInstance(this).messageDao().pendingSyncCount()
            val lastAt = store.getLastSyncAt()
            val lastError = store.getLastError()
            runOnUiThread {
                textPending.text = pending.toString()
                textLastAt.text = if (lastAt > 0) {
                    SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(lastAt))
                } else "—"
                if (lastError.isNotEmpty()) {
                    textLastError.visibility = View.VISIBLE
                    textLastError.text = lastError
                } else {
                    textLastError.visibility = View.GONE
                }
            }
        }
    }
}
