package com.example.pingtolk;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class ChatActivity extends AppCompatActivity {

    TextView textRoomName, textRoomCode;
    EditText editMessage;
    ImageButton btnSend, btnImage;
    ImageView btnBack;
    RecyclerView recyclerMessages;

    FirebaseFirestore db;
    CollectionReference chatRef;
    MessageAdapter adapter;
    ArrayList<Message> messageList = new ArrayList<>();

    String familyCode, nickname, profileUrl;
    private String lastDate = "";

    private final int REQUEST_PERMISSION = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        // 초기화
        familyCode = getIntent().getStringExtra("familyCode");
        nickname = getIntent().getStringExtra("nickname");

        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        profileUrl = prefs.getString("profile_image_url", null);

        // UI 연결
        textRoomName = findViewById(R.id.textRoomName);
        textRoomCode = findViewById(R.id.textRoomCode);
        editMessage = findViewById(R.id.editMessage);
        btnSend = findViewById(R.id.btnSend);
        btnImage = findViewById(R.id.btnImage);
        btnBack = findViewById(R.id.btnBack);
        recyclerMessages = findViewById(R.id.recyclerMessages);

        textRoomName.setText(R.string.room_name);
        String roomCode = getIntent().getStringExtra("roomName");
        textRoomCode.setText(roomCode != null ? roomCode : getString(R.string.room_code));

        db = FirebaseFirestore.getInstance();
        chatRef = db.collection("rooms").document(familyCode).collection("messages");

        // ✅ 입장 메시지 중복 방지 처리
        db.collection("rooms")
                .document(familyCode)
                .collection("enteredUsers")
                .document(nickname)
                .get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) {
                        Message welcomeMsg = new Message("SYSTEM", nickname + "님이 입장하셨습니다", System.currentTimeMillis());
                        welcomeMsg.setProfileImageUrl(null);
                        chatRef.add(welcomeMsg);

                        db.collection("rooms")
                                .document(familyCode)
                                .collection("enteredUsers")
                                .document(nickname)
                                .set(new HashMap<>());
                    }
                });

        // RecyclerView
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        recyclerMessages.setLayoutManager(layoutManager);
        adapter = new MessageAdapter(this, messageList, nickname);
        recyclerMessages.setAdapter(adapter);

        // 메시지 실시간 수신
        listenForMessages();

        // 메시지 전송 (엔터)
        editMessage.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                btnSend.performClick();
                return true;
            }
            return false;
        });

        // 메시지 전송 (버튼)
        btnSend.setOnClickListener(v -> {
            String text = editMessage.getText().toString().trim();
            if (!text.isEmpty()) {
                Message msg = new Message(nickname, text, System.currentTimeMillis());
                msg.setProfileImageUrl(profileUrl);
                msg.setImage(false);
                HashMap<String, Object> msgMap = new HashMap<>();
                msgMap.put("sender", msg.getSender());
                msgMap.put("text", msg.getText());
                msgMap.put("timestamp", msg.getTimestamp());
                msgMap.put("profileImageUrl", msg.getProfileImageUrl());
                msgMap.put("isImage", false);
                msgMap.put("imageUrl", null);
                msgMap.put("isDateSeparator", false);
                chatRef.add(msgMap);
                editMessage.setText("");
            }
        });

        // 이미지 전송 버튼
        btnImage.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQUEST_PERMISSION);
            } else {
                openImagePicker.launch("image/*");
            }
        });

        // 뒤로가기
        btnBack.setOnClickListener(v -> {
            Intent intent = new Intent(ChatActivity.this, RoomListActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });

        ImageView btnChatMenu = findViewById(R.id.btnChatMenu);
        btnChatMenu.setOnClickListener(v -> showChatInfoDialog());
    }

    // 이미지 선택기
    private final ActivityResultLauncher<String> openImagePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    resizeAndUploadImage(uri);
                }
            });

    // 이미지 리사이징 및 업로드
    private void resizeAndUploadImage(Uri uri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            Bitmap original = BitmapFactory.decodeStream(inputStream);

            int canvasSize = 800;
            Bitmap output = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            canvas.drawColor(Color.DKGRAY); // dark background

            int originalWidth = original.getWidth();
            int originalHeight = original.getHeight();
            float scale = Math.min((float) canvasSize / originalWidth, (float) canvasSize / originalHeight);
            int scaledWidth = Math.round(originalWidth * scale);
            int scaledHeight = Math.round(originalHeight * scale);
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(original, scaledWidth, scaledHeight, true);

            int left = (canvasSize - scaledWidth) / 2;
            int top = (canvasSize - scaledHeight) / 2;
            canvas.drawBitmap(scaledBitmap, left, top, null);

            Bitmap resized = output;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            resized.compress(Bitmap.CompressFormat.JPEG, 70, baos);
            byte[] data = baos.toByteArray();

            // 스토리지에 업로드
            StorageReference storageRef = FirebaseStorage.getInstance().getReference();
            String fileName = "chat_images/" + System.currentTimeMillis() + ".jpg";
            StorageReference imageRef = storageRef.child(fileName);

            UploadTask uploadTask = imageRef.putBytes(data);
            uploadTask.addOnSuccessListener(taskSnapshot -> imageRef.getDownloadUrl().addOnSuccessListener(downloadUri -> {
                Message imageMsg = new Message(nickname, null, System.currentTimeMillis());
                imageMsg.setProfileImageUrl(profileUrl);
                imageMsg.setImageUrl(downloadUri.toString());
                imageMsg.setImage(true);

                HashMap<String, Object> msgMap = new HashMap<>();
                msgMap.put("sender", imageMsg.getSender());
                msgMap.put("text", "");
                msgMap.put("timestamp", imageMsg.getTimestamp());
                msgMap.put("profileImageUrl", imageMsg.getProfileImageUrl());
                msgMap.put("isImage", true);
                msgMap.put("imageUrl", imageMsg.getImageUrl());
                msgMap.put("isDateSeparator", false);

                chatRef.add(msgMap);
            })).addOnFailureListener(e -> {
                Toast.makeText(this, getString(R.string.toast_upload_fail), Toast.LENGTH_SHORT).show();
                Log.e("ChatActivity", "이미지 업로드 실패", e);
            });

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, getString(R.string.toast_upload_fail), Toast.LENGTH_SHORT).show();
        }
    }

    // 채팅 수신
    private void listenForMessages() {
        chatRef.orderBy("timestamp")
                .addSnapshotListener((QuerySnapshot value, com.google.firebase.firestore.FirebaseFirestoreException error) -> {
                    if (value == null || error != null) return;

                    List<Message> newMessages = new ArrayList<>();
                    for (DocumentChange change : value.getDocumentChanges()) {
                        if (change.getType() == DocumentChange.Type.ADDED) {
                            Message msg = change.getDocument().toObject(Message.class);
                            newMessages.add(msg);
                        }
                    }

                    // 정렬 수행 (timestamp 기준 오름차순)
                    Collections.sort(newMessages, (a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()));

                    for (Message msg : newMessages) {
                        String msgDate = new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
                                .format(new Date(msg.getTimestamp()));
                        if (!msgDate.equals(lastDate)) {
                            messageList.add(Message.createDateSeparator(msg.getTimestamp()));
                            lastDate = msgDate;
                        }
                        messageList.add(msg);
                        adapter.notifyItemInserted(messageList.size() - 1);
                        recyclerMessages.scrollToPosition(messageList.size() - 1);
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        nickname = prefs.getString("nickname", nickname); // fallback to old nickname if not found
        profileUrl = prefs.getString("profile_image_url", profileUrl);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.chat_menu, menu); // chat_menu.xml should be created in res/menu
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_chat_info) {
            // Open a bottom sheet or dialog with profile info, members, and "Leave Room" button
            showChatInfoDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showChatInfoDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_chat_info, null);

        ImageView imageProfile = view.findViewById(R.id.imageProfile);
        TextView textNickname = view.findViewById(R.id.textNickname);
        RecyclerView recyclerMembers = view.findViewById(R.id.recyclerMembers);
        recyclerMembers.setLayoutManager(new LinearLayoutManager(this));
        Button btnLeaveRoom = view.findViewById(R.id.btnLeaveRoom);

        // 내 프로필 표시
        textNickname.setText(nickname);
        if (profileUrl != null && !profileUrl.isEmpty()) {
            Glide.with(this).load(profileUrl).circleCrop().into(imageProfile);
        }

        // 참여 중인 멤버 목록 표시
        db.collection("rooms").document(familyCode)
                .collection("enteredUsers")
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    List<String> memberNames = new ArrayList<>();
                    for (var doc : querySnapshot) {
                        memberNames.add(doc.getId());
                    }
                    ChatInfoAdapter adapter = new ChatInfoAdapter(memberNames);
                    recyclerMembers.setAdapter(adapter);
                });

        // 방 나가기
        btnLeaveRoom.setOnClickListener(v -> {
            db.collection("rooms").document(familyCode)
                    .collection("enteredUsers")
                    .document(nickname)
                    .delete()
                    .addOnSuccessListener(aVoid -> {
                        Toast.makeText(this, getString(R.string.menu_logout), Toast.LENGTH_SHORT).show();
                        finish(); // 채팅방 종료
                    });
        });

        ImageView btnBack = view.findViewById(R.id.btnBack);
        AlertDialog alertDialog = new AlertDialog.Builder(this)
                .setView(view)
                .setNegativeButton("닫기", (dialog, which) -> dialog.dismiss())
                .create();
        // Back button to close the dialog and return to ChatActivity
        btnBack.setOnClickListener(v -> alertDialog.dismiss());
        alertDialog.show();
    }
}
