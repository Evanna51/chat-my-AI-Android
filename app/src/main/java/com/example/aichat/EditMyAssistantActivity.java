package com.example.aichat;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class EditMyAssistantActivity extends ThemedActivity {
    public static final String EXTRA_ASSISTANT_ID = "assistant_id";

    private MyAssistantStore store;
    private MyAssistant assistant;
    private ChatSettingsFormModule formModule;
    private ImageView imageAvatarPreview;
    private TextView textAvatarPreview;
    private TextInputEditText editName;
    private TextInputEditText editAvatar;
    private final ActivityResultLauncher<String> imagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onAvatarImagePicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_my_assistant);

        store = new MyAssistantStore(this);
        String assistantId = getIntent().getStringExtra(EXTRA_ASSISTANT_ID);
        assistant = assistantId != null ? store.getById(assistantId) : null;
        if (assistant == null) assistant = store.createEmpty();

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        editName = findViewById(R.id.editAssistantName);
        TextInputEditText editPrompt = findViewById(R.id.editAssistantPrompt);
        editAvatar = findViewById(R.id.editAssistantAvatar);
        RadioGroup radioType = findViewById(R.id.radioAssistantType);
        MaterialButton btnSave = findViewById(R.id.btnSaveAssistant);
        MaterialButton btnDelete = findViewById(R.id.btnDeleteAssistant);
        MaterialButton btnPickAvatar = findViewById(R.id.btnPickAssistantAvatar);
        MaterialButton btnClearAvatarImage = findViewById(R.id.btnClearAssistantAvatarImage);
        imageAvatarPreview = findViewById(R.id.imageAssistantAvatarPreview);
        textAvatarPreview = findViewById(R.id.textAssistantAvatarPreview);

        editName.setText(assistant.name);
        editPrompt.setText(assistant.prompt);
        editAvatar.setText(assistant.avatar);
        if ("writer".equals(assistant.type)) {
            radioType.check(R.id.typeWriter);
        } else if ("character".equals(assistant.type)) {
            radioType.check(R.id.typeCharacter);
        } else {
            radioType.check(R.id.typeDefault);
        }
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
            assistant.avatar = editAvatar.getText() != null ? editAvatar.getText().toString().trim() : "";
            int checkedType = radioType.getCheckedRadioButtonId();
            if (checkedType == R.id.typeWriter) {
                assistant.type = "writer";
            } else if (checkedType == R.id.typeCharacter) {
                assistant.type = "character";
            } else {
                assistant.type = "default";
            }
            assistant.options = formModule.collect();
            if (assistant.options != null && (assistant.options.systemPrompt == null || assistant.options.systemPrompt.isEmpty())) {
                assistant.options.systemPrompt = assistant.prompt;
            }
            assistant.updatedAt = System.currentTimeMillis();
            store.save(assistant);
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
        String compressedBase64 = compressImageToBase64(uri, 256, 80);
        if (compressedBase64 == null || compressedBase64.isEmpty()) {
            Toast.makeText(this, "图片处理失败，请重试", Toast.LENGTH_SHORT).show();
            return;
        }
        assistant.avatarImageBase64 = compressedBase64;
        refreshAvatarPreview();
    }

    private void refreshAvatarPreview() {
        String previewName = editName != null && editName.getText() != null ? editName.getText().toString().trim() : assistant.name;
        AssistantAvatarHelper.bindAvatar(imageAvatarPreview, textAvatarPreview, assistant, previewName);
    }

    private String compressImageToBase64(Uri uri, int maxSize, int quality) {
        Bitmap bitmap = decodeSampledBitmap(uri, maxSize);
        if (bitmap == null) return null;
        Bitmap scaled = scaleBitmapWithin(bitmap, maxSize);
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
}
