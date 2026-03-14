package com.example.aichat;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.List;
import android.content.pm.ResolveInfo;

public class EditMyAssistantActivity extends ThemedActivity {
    private static final String TAG = "EditMyAssistantActivity";
    public static final String EXTRA_ASSISTANT_ID = "assistant_id";

    private MyAssistantStore store;
    private MyAssistant assistant;
    private ChatSettingsFormModule formModule;
    private ImageView imageAvatarPreview;
    private TextView textAvatarPreview;
    private TextInputEditText editName;
    private TextInputEditText editAvatar;
    private TextInputEditText editFirstDialogue;
    private CharacterMemoryService characterMemoryService;
    private Uri pendingCropSourceUri;
    private Uri pendingCropOutputUri;
    private final ActivityResultLauncher<String> imagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onAvatarImagePicked);
    private final ActivityResultLauncher<Intent> cropImageLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result ->
                    onAvatarCropResult(result.getResultCode(), result.getData()));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_my_assistant);

        store = new MyAssistantStore(this);
        characterMemoryService = new CharacterMemoryService(this);
        String assistantId = getIntent().getStringExtra(EXTRA_ASSISTANT_ID);
        assistant = assistantId != null ? store.getById(assistantId) : null;
        if (assistant == null) assistant = store.createEmpty();

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        editName = findViewById(R.id.editAssistantName);
        TextInputEditText editPrompt = findViewById(R.id.editAssistantPrompt);
        editFirstDialogue = findViewById(R.id.editAssistantFirstDialogue);
        editAvatar = findViewById(R.id.editAssistantAvatar);
        FormInputScrollHelper.enableFor(editPrompt, editFirstDialogue);
        RadioGroup radioType = findViewById(R.id.radioAssistantType);
        View layoutCharacterOptions = findViewById(R.id.layoutCharacterOptions);
        MaterialCheckBox checkCharacterAutoLife = findViewById(R.id.checkCharacterAutoLife);
        MaterialCheckBox checkCharacterActiveMessage = findViewById(R.id.checkCharacterActiveMessage);
        MaterialButton btnSave = findViewById(R.id.btnSaveAssistant);
        MaterialButton btnDelete = findViewById(R.id.btnDeleteAssistant);
        MaterialButton btnPickAvatar = findViewById(R.id.btnPickAssistantAvatar);
        MaterialButton btnClearAvatarImage = findViewById(R.id.btnClearAssistantAvatarImage);
        imageAvatarPreview = findViewById(R.id.imageAssistantAvatarPreview);
        textAvatarPreview = findViewById(R.id.textAssistantAvatarPreview);

        editName.setText(assistant.name);
        editPrompt.setText(assistant.prompt);
        if (editFirstDialogue != null) editFirstDialogue.setText(assistant.firstDialogue);
        editAvatar.setText(assistant.avatar);
        if ("writer".equals(assistant.type)) {
            radioType.check(R.id.typeWriter);
        } else if ("character".equals(assistant.type)) {
            radioType.check(R.id.typeCharacter);
        } else {
            radioType.check(R.id.typeDefault);
        }
        if (checkCharacterAutoLife != null) {
            checkCharacterAutoLife.setChecked(assistant.allowAutoLife);
        }
        if (checkCharacterActiveMessage != null) {
            checkCharacterActiveMessage.setChecked(assistant.allowProactiveMessage);
        }
        updateCharacterOptionsVisibility(layoutCharacterOptions, radioType.getCheckedRadioButtonId());
        radioType.setOnCheckedChangeListener((group, checkedId) ->
                updateCharacterOptionsVisibility(layoutCharacterOptions, checkedId));
        refreshAvatarPreview();

        formModule = new ChatSettingsFormModule(this, findViewById(R.id.chatSettingsRoot));
        formModule.setOptions(assistant.options != null ? assistant.options : new SessionChatOptions());

        btnPickAvatar.setOnClickListener(v -> imagePickerLauncher.launch("image/*"));
        btnClearAvatarImage.setOnClickListener(v -> {
            assistant.avatarImageBase64 = "";
            refreshAvatarPreview();
        });

        btnSave.setOnClickListener(v -> {
            String name = editName.getText() != null ? editName.getText().toString().trim() : "";
            if (name.isEmpty()) {
                Toast.makeText(this, "请填写助手名字", Toast.LENGTH_SHORT).show();
                return;
            }
            assistant.name = name;
            assistant.prompt = editPrompt.getText() != null ? editPrompt.getText().toString().trim() : "";
            assistant.firstDialogue = editFirstDialogue != null && editFirstDialogue.getText() != null
                    ? editFirstDialogue.getText().toString().trim() : "";
            assistant.avatar = editAvatar.getText() != null ? editAvatar.getText().toString().trim() : "";
            int checkedType = radioType.getCheckedRadioButtonId();
            if (checkedType == R.id.typeWriter) {
                assistant.type = "writer";
            } else if (checkedType == R.id.typeCharacter) {
                assistant.type = "character";
            } else {
                assistant.type = "default";
            }
            assistant.allowAutoLife = checkCharacterAutoLife != null && checkCharacterAutoLife.isChecked();
            assistant.allowProactiveMessage = checkCharacterActiveMessage != null && checkCharacterActiveMessage.isChecked();
            assistant.options = formModule.collect();
            assistant.updatedAt = System.currentTimeMillis();
            store.save(assistant);
            if ("character".equals(assistant.type)) {
                reportCharacterProfileAsync(assistant);
            }
            finish();
        });

        btnDelete.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle("删除助手")
                .setMessage("确定删除该助手吗？")
                .setPositiveButton("删除", (d, w) -> {
                    store.delete(assistant.id);
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    private void onAvatarImagePicked(Uri uri) {
        if (uri == null) return;
        pendingCropSourceUri = uri;
        if (!launchSystemCrop(uri)) {
            setAvatarFromUri(uri);
        }
    }

    private void onAvatarCropResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK) return;
        Bitmap bitmap = null;
        if (pendingCropOutputUri != null) {
            bitmap = decodeSampledBitmap(pendingCropOutputUri, 512);
        }
        if (data != null && data.getExtras() != null) {
            Object value = data.getExtras().get("data");
            if (value instanceof Bitmap) {
                bitmap = (Bitmap) value;
            }
        }
        if (bitmap != null) {
            setAvatarFromBitmap(bitmap);
            return;
        }
        if (pendingCropSourceUri != null) {
            setAvatarFromUri(pendingCropSourceUri);
            return;
        }
        Toast.makeText(this, "图片处理失败，请重试", Toast.LENGTH_SHORT).show();
    }

    private boolean launchSystemCrop(Uri uri) {
        Intent cropIntent = new Intent("com.android.camera.action.CROP");
        cropIntent.setDataAndType(uri, "image/*");
        Uri outputUri = createCropOutputUri();
        pendingCropOutputUri = outputUri;
        cropIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        cropIntent.putExtra("crop", "true");
        cropIntent.putExtra("aspectX", 1);
        cropIntent.putExtra("aspectY", 1);
        cropIntent.putExtra("outputX", 256);
        cropIntent.putExtra("outputY", 256);
        cropIntent.putExtra("scale", true);
        cropIntent.putExtra("return-data", false);
        if (outputUri != null) {
            cropIntent.putExtra("output", outputUri);
            cropIntent.putExtra("outputFormat", Bitmap.CompressFormat.JPEG.toString());
        }
        cropIntent.putExtra("circleCrop", "true");
        List<ResolveInfo> handlers = getPackageManager().queryIntentActivities(cropIntent, 0);
        if (handlers == null || handlers.isEmpty()) {
            return false;
        }
        for (ResolveInfo resolveInfo : handlers) {
            if (resolveInfo == null || resolveInfo.activityInfo == null) continue;
            String packageName = resolveInfo.activityInfo.packageName;
            try {
                grantUriPermission(packageName, uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if (outputUri != null) {
                    grantUriPermission(packageName, outputUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                }
            } catch (Exception ignored) {}
        }
        try {
            cropImageLauncher.launch(cropIntent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Uri createCropOutputUri() {
        try {
            File file = new File(getCacheDir(), "avatar_crop_" + System.currentTimeMillis() + ".jpg");
            return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void setAvatarFromUri(Uri uri) {
        String compressedBase64 = compressImageToBase64(uri, 256, 80);
        if (compressedBase64 == null || compressedBase64.isEmpty()) {
            Toast.makeText(this, "图片处理失败，请重试", Toast.LENGTH_SHORT).show();
            return;
        }
        assistant.avatarImageBase64 = compressedBase64;
        refreshAvatarPreview();
    }

    private void setAvatarFromBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            Toast.makeText(this, "图片处理失败，请重试", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap squared = cropCenterSquare(bitmap);
        Bitmap scaled = scaleBitmapWithin(squared, 256);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        boolean compressed = scaled.compress(Bitmap.CompressFormat.JPEG, 85, outputStream);
        if (!compressed) {
            Toast.makeText(this, "图片处理失败，请重试", Toast.LENGTH_SHORT).show();
            return;
        }
        assistant.avatarImageBase64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP);
        refreshAvatarPreview();
    }

    private void refreshAvatarPreview() {
        String previewName = editName != null && editName.getText() != null ? editName.getText().toString().trim() : assistant.name;
        AssistantAvatarHelper.bindAvatar(imageAvatarPreview, textAvatarPreview, assistant, previewName);
    }

    private String compressImageToBase64(Uri uri, int maxSize, int quality) {
        Bitmap bitmap = decodeSampledBitmap(uri, maxSize);
        if (bitmap == null) return null;
        Bitmap squared = cropCenterSquare(bitmap);
        Bitmap scaled = scaleBitmapWithin(squared, maxSize);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        boolean compressed = scaled.compress(Bitmap.CompressFormat.JPEG, quality, outputStream);
        if (!compressed) return null;
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP);
    }

    private Bitmap decodeSampledBitmap(Uri uri, int reqSize) {
        try {
            BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
            boundsOptions.inJustDecodeBounds = true;
            InputStream boundsStream = getContentResolver().openInputStream(uri);
            if (boundsStream == null) return null;
            BitmapFactory.decodeStream(boundsStream, null, boundsOptions);
            boundsStream.close();

            BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
            decodeOptions.inSampleSize = calculateInSampleSize(boundsOptions, reqSize, reqSize);
            InputStream decodeStream = getContentResolver().openInputStream(uri);
            if (decodeStream == null) return null;
            Bitmap decoded = BitmapFactory.decodeStream(decodeStream, null, decodeOptions);
            decodeStream.close();
            return decoded;
        } catch (Exception e) {
            return null;
        }
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private Bitmap scaleBitmapWithin(Bitmap bitmap, int maxSize) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= maxSize && height <= maxSize) return bitmap;
        float ratio = (float) width / (float) height;
        int targetWidth;
        int targetHeight;
        if (ratio > 1f) {
            targetWidth = maxSize;
            targetHeight = Math.round(maxSize / ratio);
        } else {
            targetHeight = maxSize;
            targetWidth = Math.round(maxSize * ratio);
        }
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
    }

    private Bitmap cropCenterSquare(Bitmap bitmap) {
        if (bitmap == null) return null;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) return bitmap;
        int size = Math.min(width, height);
        int x = (width - size) / 2;
        int y = (height - size) / 2;
        return Bitmap.createBitmap(bitmap, x, y, size, size);
    }

    private void reportCharacterProfileAsync(MyAssistant target) {
        if (target == null || characterMemoryService == null) return;
        if (!characterMemoryService.isEnabled()) return;
        final String assistantId = target.id != null ? target.id.trim() : "";
        final String characterName = target.name != null ? target.name.trim() : "";
        final String characterBackground = target.prompt != null ? target.prompt.trim() : "";
        final boolean allowAutoLife = target.allowAutoLife;
        final boolean allowProactiveMessage = target.allowProactiveMessage;
        if (assistantId.isEmpty() || characterName.isEmpty() || characterBackground.isEmpty()) return;
        new Thread(() -> {
            try {
                characterMemoryService.reportCharacterProfile(
                        assistantId,
                        characterName,
                        characterBackground,
                        allowAutoLife,
                        allowProactiveMessage);
            } catch (Exception e) {
                Log.w(TAG, "report character profile failed: " + (e != null ? e.getMessage() : ""));
            }
        }).start();
    }

    private void updateCharacterOptionsVisibility(View layoutCharacterOptions, int checkedTypeId) {
        if (layoutCharacterOptions == null) return;
        layoutCharacterOptions.setVisibility(checkedTypeId == R.id.typeCharacter ? View.VISIBLE : View.GONE);
    }
}
