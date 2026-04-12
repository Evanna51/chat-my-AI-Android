package com.example.aichat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import android.content.pm.ResolveInfo

class EditMyAssistantActivity : ThemedActivity() {

    companion object {
        private const val TAG = "EditMyAssistantActivity"
        const val EXTRA_ASSISTANT_ID = "assistant_id"
    }

    private lateinit var store: MyAssistantStore
    private lateinit var assistant: MyAssistant
    private lateinit var formModule: ChatSettingsFormModule
    private lateinit var imageAvatarPreview: ImageView
    private lateinit var textAvatarPreview: TextView
    private var textAvatarAddHint: TextView? = null
    private lateinit var editName: TextInputEditText
    private var editSystemPrompt: TextInputEditText? = null
    private var editFirstDialogue: TextInputEditText? = null
    private lateinit var characterMemoryService: CharacterMemoryService
    private var pendingCropSourceUri: Uri? = null
    private var pendingCropOutputUri: Uri? = null

    private val imagePickerLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> onAvatarImagePicked(uri) }

    private val cropImageLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onAvatarCropResult(result.resultCode, result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_my_assistant)

        store = MyAssistantStore(this)
        characterMemoryService = CharacterMemoryService(this)
        val assistantId = intent.getStringExtra(EXTRA_ASSISTANT_ID)
        assistant = (if (assistantId != null) store.getById(assistantId) else null) ?: store.createEmpty()

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        editName = findViewById(R.id.editAssistantName)
        editSystemPrompt = findViewById(R.id.editAssistantSystemPrompt)
        editFirstDialogue = findViewById(R.id.editAssistantFirstDialogue)
        FormInputScrollHelper.enableFor(editSystemPrompt, editFirstDialogue)
        val radioType: RadioGroup = findViewById(R.id.radioAssistantType)
        val layoutCharacterOptions: View? = findViewById(R.id.layoutCharacterOptions)
        val checkCharacterAutoLife: MaterialCheckBox? = findViewById(R.id.checkCharacterAutoLife)
        val checkCharacterActiveMessage: MaterialCheckBox? = findViewById(R.id.checkCharacterActiveMessage)
        val switchAutoChapterPlanWriter: MaterialSwitch? = findViewById(R.id.switchAutoChapterPlanWriter)
        val btnSave: MaterialButton = findViewById(R.id.btnSaveAssistant)
        val btnDelete: MaterialButton = findViewById(R.id.btnDeleteAssistant)
        val avatarPickerContainer: View? = findViewById(R.id.avatarPickerContainer)
        val btnClearAvatarImage: View? = findViewById(R.id.btnClearAssistantAvatarImage)
        imageAvatarPreview = findViewById(R.id.imageAssistantAvatarPreview)
        textAvatarPreview = findViewById(R.id.textAssistantAvatarPreview)
        textAvatarAddHint = findViewById(R.id.textAvatarAddHint)

        editName.setText(assistant.name)
        val initialOptions = assistant.options
        if (initialOptions != null) {
            editSystemPrompt?.setText(initialOptions.systemPrompt)
        }
        editFirstDialogue?.setText(assistant.firstDialogue)
        when {
            "writer" == assistant.type -> radioType.check(R.id.typeWriter)
            "character" == assistant.type -> radioType.check(R.id.typeCharacter)
            else -> radioType.check(R.id.typeDefault)
        }
        checkCharacterAutoLife?.isChecked = assistant.allowAutoLife
        checkCharacterActiveMessage?.isChecked = assistant.allowProactiveMessage
        if (switchAutoChapterPlanWriter != null && initialOptions != null) {
            switchAutoChapterPlanWriter.isChecked = initialOptions.autoChapterPlan
        }
        updateWriterOnlySettingsVisibility(switchAutoChapterPlanWriter, radioType.checkedRadioButtonId)
        updateCharacterOptionsVisibility(layoutCharacterOptions, radioType.checkedRadioButtonId)
        radioType.setOnCheckedChangeListener { _, checkedId ->
            updateWriterOnlySettingsVisibility(switchAutoChapterPlanWriter, checkedId)
            updateCharacterOptionsVisibility(layoutCharacterOptions, checkedId)
        }
        refreshAvatarPreview()
        editName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshAvatarPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        formModule = ChatSettingsFormModule(this, findViewById(R.id.chatSettingsRoot))
        if (assistant.options == null) assistant.options = SessionChatOptions()
        val opts = assistant.options
        if (opts != null && (opts.systemPrompt == null || opts.systemPrompt.trim().isEmpty())
            && assistant.prompt != null && assistant.prompt.trim().isNotEmpty()
        ) {
            opts.systemPrompt = assistant.prompt.trim()
        }
        formModule.setOptions(assistant.options ?: SessionChatOptions())
        val layoutSystemPromptInForm: View? = findViewById(R.id.layoutSystemPrompt)
        layoutSystemPromptInForm?.visibility = View.GONE
        val switchAutoPlanInForm: View? = findViewById(R.id.switchAutoChapterPlan)
        switchAutoPlanInForm?.visibility = View.GONE

        avatarPickerContainer?.setOnClickListener { imagePickerLauncher.launch("image/*") }
        btnClearAvatarImage?.setOnClickListener {
            assistant.avatarImageBase64 = ""
            refreshAvatarPreview()
        }

        btnSave.setOnClickListener {
            val name = editName.text?.toString()?.trim() ?: ""
            if (name.isEmpty()) {
                Toast.makeText(this, "请填写助手名字", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            assistant.name = name
            assistant.prompt = ""
            assistant.firstDialogue = editFirstDialogue?.text?.toString()?.trim() ?: ""
            assistant.avatar = ""
            val checkedType = radioType.checkedRadioButtonId
            assistant.type = when (checkedType) {
                R.id.typeWriter -> "writer"
                R.id.typeCharacter -> "character"
                else -> "default"
            }
            assistant.allowAutoLife = checkCharacterAutoLife?.isChecked == true
            assistant.allowProactiveMessage = checkCharacterActiveMessage?.isChecked == true
            assistant.options = formModule.collect()
            if (assistant.options == null) assistant.options = SessionChatOptions()
            val savedOptions = assistant.options
            if (savedOptions != null) {
                savedOptions.systemPrompt = editSystemPrompt?.text?.toString()?.trim() ?: ""
                savedOptions.autoChapterPlan = "writer" == assistant.type
                        && switchAutoChapterPlanWriter != null
                        && switchAutoChapterPlanWriter.isChecked
                if ("writer" != assistant.type) {
                    savedOptions.autoChapterPlan = false
                }
            }
            assistant.updatedAt = System.currentTimeMillis()
            store.save(assistant)
            if ("character" == assistant.type) {
                reportCharacterProfileAsync(assistant)
            }
            finish()
        }

        btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("删除助手")
                .setMessage("确定删除该助手吗？")
                .setPositiveButton("删除") { _, _ ->
                    store.delete(assistant.id)
                    finish()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun onAvatarImagePicked(uri: Uri?) {
        if (uri == null) return
        pendingCropSourceUri = uri
        if (!launchSystemCrop(uri)) {
            setAvatarFromUri(uri)
        }
    }

    private fun onAvatarCropResult(resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK) return
        var bitmap: Bitmap? = null
        if (pendingCropOutputUri != null) {
            bitmap = decodeSampledBitmap(pendingCropOutputUri!!, 512)
        }
        if (data?.extras != null) {
            val value = data.extras!!.get("data")
            if (value is Bitmap) {
                bitmap = value
            }
        }
        if (bitmap != null) {
            setAvatarFromBitmap(bitmap)
            return
        }
        if (pendingCropSourceUri != null) {
            setAvatarFromUri(pendingCropSourceUri!!)
            return
        }
        Toast.makeText(this, "图片处理失败，请重试", Toast.LENGTH_SHORT).show()
    }

    private fun launchSystemCrop(uri: Uri): Boolean {
        val cropIntent = Intent("com.android.camera.action.CROP")
        cropIntent.setDataAndType(uri, "image/*")
        val outputUri = createCropOutputUri()
        pendingCropOutputUri = outputUri
        cropIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        cropIntent.putExtra("crop", "true")
        cropIntent.putExtra("aspectX", 1)
        cropIntent.putExtra("aspectY", 1)
        cropIntent.putExtra("outputX", 256)
        cropIntent.putExtra("outputY", 256)
        cropIntent.putExtra("scale", true)
        cropIntent.putExtra("return-data", false)
        if (outputUri != null) {
            cropIntent.putExtra("output", outputUri)
            cropIntent.putExtra("outputFormat", Bitmap.CompressFormat.JPEG.toString())
        }
        cropIntent.putExtra("circleCrop", "true")
        val handlers: List<ResolveInfo>? = packageManager.queryIntentActivities(cropIntent, 0)
        if (handlers.isNullOrEmpty()) {
            return false
        }
        for (resolveInfo in handlers) {
            if (resolveInfo?.activityInfo == null) continue
            val packageName = resolveInfo.activityInfo.packageName
            try {
                grantUriPermission(packageName, uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (outputUri != null) {
                    grantUriPermission(packageName, outputUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
            } catch (ignored: Exception) {}
        }
        return try {
            cropImageLauncher.launch(cropIntent)
            true
        } catch (ignored: Exception) {
            false
        }
    }

    private fun createCropOutputUri(): Uri? {
        return try {
            val file = File(cacheDir, "avatar_crop_${System.currentTimeMillis()}.jpg")
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (ignored: Exception) {
            null
        }
    }

    private fun setAvatarFromUri(uri: Uri) {
        val compressedBase64 = compressImageToBase64(uri, 256, 80)
        if (compressedBase64.isNullOrEmpty()) {
            Toast.makeText(this, "图片处理失败，请重试", Toast.LENGTH_SHORT).show()
            return
        }
        assistant.avatarImageBase64 = compressedBase64
        refreshAvatarPreview()
    }

    private fun setAvatarFromBitmap(bitmap: Bitmap?) {
        if (bitmap == null) {
            Toast.makeText(this, "图片处理失败，请重试", Toast.LENGTH_SHORT).show()
            return
        }
        val squared = cropCenterSquare(bitmap)
        val scaled = scaleBitmapWithin(squared, 256)
        val outputStream = ByteArrayOutputStream()
        val compressed = scaled.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        if (!compressed) {
            Toast.makeText(this, "图片处理失败，请重试", Toast.LENGTH_SHORT).show()
            return
        }
        assistant.avatarImageBase64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        refreshAvatarPreview()
    }

    private fun refreshAvatarPreview() {
        val previewName = editName.text?.toString()?.trim() ?: assistant.name
        AssistantAvatarHelper.bindAvatar(imageAvatarPreview, textAvatarPreview, assistant, previewName)
        val hasImage = assistant.avatarImageBase64 != null && assistant.avatarImageBase64.trim().isNotEmpty()
        val clearBtn: View? = findViewById(R.id.btnClearAssistantAvatarImage)
        clearBtn?.visibility = if (hasImage) View.VISIBLE else View.INVISIBLE
        textAvatarAddHint?.visibility = if (hasImage) View.GONE else View.VISIBLE
    }

    private fun compressImageToBase64(uri: Uri, maxSize: Int, quality: Int): String? {
        val bitmap = decodeSampledBitmap(uri, maxSize) ?: return null
        val squared = cropCenterSquare(bitmap)
        val scaled = scaleBitmapWithin(squared, maxSize)
        val outputStream = ByteArrayOutputStream()
        val compressed = scaled.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        if (!compressed) return null
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun decodeSampledBitmap(uri: Uri, reqSize: Int): Bitmap? {
        return try {
            val boundsOptions = BitmapFactory.Options()
            boundsOptions.inJustDecodeBounds = true
            val boundsStream: InputStream = contentResolver.openInputStream(uri) ?: return null
            BitmapFactory.decodeStream(boundsStream, null, boundsOptions)
            boundsStream.close()

            val decodeOptions = BitmapFactory.Options()
            decodeOptions.inSampleSize = calculateInSampleSize(boundsOptions, reqSize, reqSize)
            val decodeStream: InputStream = contentResolver.openInputStream(uri) ?: return null
            val decoded = BitmapFactory.decodeStream(decodeStream, null, decodeOptions)
            decodeStream.close()
            decoded
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun scaleBitmapWithin(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxSize && height <= maxSize) return bitmap
        val ratio = width.toFloat() / height.toFloat()
        val targetWidth: Int
        val targetHeight: Int
        if (ratio > 1f) {
            targetWidth = maxSize
            targetHeight = Math.round(maxSize / ratio)
        } else {
            targetHeight = maxSize
            targetWidth = Math.round(maxSize * ratio)
        }
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun cropCenterSquare(bitmap: Bitmap?): Bitmap {
        if (bitmap == null) return bitmap!!
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return bitmap
        val size = minOf(width, height)
        val x = (width - size) / 2
        val y = (height - size) / 2
        return Bitmap.createBitmap(bitmap, x, y, size, size)
    }

    private fun reportCharacterProfileAsync(target: MyAssistant?) {
        if (target == null || !characterMemoryService.isEnabled()) return
        val assistantId = target.id?.trim() ?: ""
        val characterName = target.name?.trim() ?: ""
        var background = ""
        val targetOptions = target.options
        if (targetOptions?.systemPrompt != null) {
            background = targetOptions.systemPrompt.trim()
        }
        if (background.isEmpty() && target.prompt != null) {
            background = target.prompt.trim()
        }
        val characterBackground = background
        val allowAutoLife = target.allowAutoLife
        val allowProactiveMessage = target.allowProactiveMessage
        if (assistantId.isEmpty() || characterName.isEmpty() || characterBackground.isEmpty()) return
        Thread {
            try {
                characterMemoryService.reportCharacterProfile(
                    assistantId,
                    characterName,
                    characterBackground,
                    allowAutoLife,
                    allowProactiveMessage
                )
            } catch (e: Exception) {
                Log.w(TAG, "report character profile failed: ${e.message ?: ""}")
            }
        }.start()
    }

    private fun updateCharacterOptionsVisibility(layoutCharacterOptions: View?, checkedTypeId: Int) {
        layoutCharacterOptions?.visibility =
            if (checkedTypeId == R.id.typeCharacter) View.VISIBLE else View.GONE
    }

    private fun updateWriterOnlySettingsVisibility(writerSettingView: View?, checkedTypeId: Int) {
        writerSettingView?.visibility =
            if (checkedTypeId == R.id.typeWriter) View.VISIBLE else View.GONE
    }
}
