package com.example.traffigo.activities;

import android.app.ProgressDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.traffigo.R;
import com.example.traffigo.databinding.ActivityProfileBinding;
import com.example.traffigo.utils.AvatarUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.io.InputStream;

public class ProfileActivity extends AppCompatActivity {

    // Khai báo ViewBinding thay cho các View đơn lẻ
    private ActivityProfileBinding binding;

    private FirebaseAuth mAuth;
    private DatabaseReference userRef;
    private Uri selectedImageUri = null;
    private boolean isPasswordVisible = false;

    private final ActivityResultLauncher<String> pickImageLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    selectedImageUri = uri;
                    // Gọi trực tiếp id qua binding
                    binding.imgAvatar.setImageURI(uri);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Khởi tạo Binding
        binding = ActivityProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mAuth = FirebaseAuth.getInstance();
        if (mAuth.getCurrentUser() != null) {
            userRef = FirebaseDatabase.getInstance().getReference("Users").child(mAuth.getCurrentUser().getUid());
        }

        setupHeader();
        setupListeners();
        loadUserData();
    }

    private void setupHeader() {
        // Tương tác trực tiếp với thẻ <include> mang id là layoutHeader
        binding.layoutHeader.tvPageTitle.setText(R.string.profile_title);

        // Ẩn nút Share vì trang Profile thường không cần thiết
        if (binding.layoutHeader.btnShare != null) {
            binding.layoutHeader.btnShare.setVisibility(View.GONE);
        }

        binding.layoutHeader.btnBack.setOnClickListener(v -> finish());

        // Không cho phép sửa Email trực tiếp
        binding.edtEmail.setEnabled(false);
    }

    private void setupListeners() {
        // Cả ảnh và dòng chữ "Đổi ảnh đại diện" đều mở thư viện ảnh
        binding.imgAvatar.setOnClickListener(v -> pickImageLauncher.launch("image/*"));
        binding.btnChangePicture.setOnClickListener(v -> pickImageLauncher.launch("image/*"));

        // Nút con mắt: ẩn/hiện mật khẩu
        binding.btnTogglePassword.setOnClickListener(v -> togglePasswordVisibility());

        binding.btnSaveChanges.setOnClickListener(v -> {
            String newName = binding.edtName.getText().toString().trim();
            String newPhone = binding.edtPhone.getText().toString().trim();
            String newPassword = binding.edtPassword.getText().toString();

            if (newName.isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_name_empty), Toast.LENGTH_SHORT).show();
                return;
            }
            if (!newPassword.isEmpty() && newPassword.length() < 6) {
                Toast.makeText(this, getString(R.string.profile_password_too_short), Toast.LENGTH_SHORT).show();
                return;
            }

            saveDataToFirebase(newName, newPhone, newPassword, selectedImageUri);
        });
    }

    private void togglePasswordVisibility() {
        if (isPasswordVisible) {
            binding.edtPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            binding.btnTogglePassword.setImageResource(R.drawable.ic_eye_hide);
        } else {
            binding.edtPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            binding.btnTogglePassword.setImageResource(R.drawable.ic_eye_show);
        }
        binding.edtPassword.setSelection(binding.edtPassword.getText().length());
        isPasswordVisible = !isPasswordVisible;
    }

    private void loadUserData() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null) {
            binding.edtName.setText(user.getDisplayName() != null ? user.getDisplayName() : "");
            binding.edtEmail.setText(user.getEmail() != null ? user.getEmail() : "");

            // Tải ảnh đại diện đã lưu (Base64 trong Realtime Database)
            binding.imgAvatar.setImageResource(R.drawable.ic_user);
            AvatarUtils.loadInto(user.getUid(), binding.imgAvatar);

            if (userRef != null) {
                userRef.child("phone").get().addOnSuccessListener(dataSnapshot -> {
                    if (dataSnapshot.exists()) {
                        binding.edtPhone.setText(dataSnapshot.getValue(String.class));
                    }
                });
            }
        }
    }

    private void saveDataToFirebase(String name, String phone, String newPassword, Uri imageUri) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        ProgressDialog dialog = new ProgressDialog(this);
        dialog.setMessage(getString(R.string.profile_updating));
        dialog.setCancelable(false);
        dialog.show();

        // 1. Cập nhật tên hiển thị (Firebase Auth)
        UserProfileChangeRequest profileUpdates = new UserProfileChangeRequest.Builder()
                .setDisplayName(name)
                .build();
        user.updateProfile(profileUpdates);

        // 2. Lưu số điện thoại + avatar (Base64) vào Realtime Database
        if (userRef != null) {
            if (!phone.isEmpty()) {
                userRef.child("phone").setValue(phone);
            }
            if (imageUri != null) {
                String base64 = encodeImage(imageUri);
                if (base64 != null) {
                    userRef.child("avatarBase64").setValue(base64);
                }
            }
        }

        // 3. Đổi mật khẩu (nếu người dùng nhập) — bước cuối để đóng dialog + báo kết quả
        if (!newPassword.isEmpty()) {
            user.updatePassword(newPassword).addOnCompleteListener(task -> {
                dialog.dismiss();
                if (task.isSuccessful()) {
                    binding.edtPassword.setText("");
                    Toast.makeText(this, getString(R.string.profile_password_updated), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, getString(R.string.profile_password_error), Toast.LENGTH_LONG).show();
                }
            });
        } else {
            dialog.dismiss();
            Toast.makeText(this, getString(R.string.toast_update_success), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Đọc ảnh từ Uri, thu nhỏ + nén thành Base64 để lưu Realtime Database.
     * Decode full-res ảnh máy ảnh (12-50MP) tốn hàng trăm MB và kẹt UI, thậm chí OOM —
     * ảnh cuối cùng chỉ cần 256px nên chỉ decode ở mức ~512px (inSampleSize) là đủ.
     */
    private String encodeImage(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, bounds);
            }

            int sampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= 512) {
                sampleSize *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            Bitmap bitmap;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                bitmap = BitmapFactory.decodeStream(is, null, opts);
            }
            return AvatarUtils.encodeToBase64(bitmap);
        } catch (Exception e) {
            return null;
        }
    }
}