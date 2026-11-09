package com.vanmypq.autoanswer

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Auto Trắc Nghiệm\n\n1) Nhập Google Gemini API key và bấm Lưu.\n" +
                "2) Bấm \"Cấp quyền chụp màn hình\" rồi chọn Bắt đầu ngay (để AI nhìn thấy công thức, hình vẽ). " +
                "Nên làm bước này TRƯỚC khi bật Trợ năng. Cần làm lại mỗi lần khởi động lại điện thoại hoặc cài lại app.\n" +
                "3) Bấm \"Bật dịch vụ Trợ năng\", tìm \"Auto Trắc Nghiệm\" và bật.\n" +
                "4) Mở app/web có câu trắc nghiệm, bấm nút nổi \"▶ AI\" để bật chế độ tự chọn; bấm lại để tắt. " +
                "Tự nhận mọi dạng: A/B/C/D, chỉ có ô tích chọn, nhiều đáp án đúng, Đúng/Sai, nhiều câu cùng màn hình. " +
                "Nhấn giữ nút nổi để sao chép dữ liệu app đọc được.\n\n" +
                "Chỉ dùng để ôn tập, luyện đề. Không dùng trong bài kiểm tra/thi thật."
            textSize = 15f
        })
        val et = EditText(this).apply {
            hint = "Google Gemini API key (AIza...)"
            setText(prefs.getString("key", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(et)
        root.addView(Button(this).apply {
            text = "Lưu API key"
            setOnClickListener {
                prefs.edit().putString("key", et.text.toString().trim()).apply()
                Toast.makeText(this@MainActivity, "Đã lưu", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(CheckBox(this).apply {
            text = "Chế độ NHANH (trả lời tức thì; tắt = suy nghĩ kỹ, chính xác hơn nhưng chậm hơn)"
            isChecked = prefs.getBoolean("fast", true)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("fast", c).apply() }
        })
        root.addView(Button(this).apply {
            text = "Cấp quyền chụp màn hình"
            setOnClickListener {
                try {
                    statusView.text = "Đang chờ bạn bấm \"Bắt đầu ngay\" trong hộp thoại của hệ thống…"
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(mpm.createScreenCaptureIntent(), 1001)
                } catch (e: Exception) {
                    statusView.text = "Không mở được hộp thoại cấp quyền: " + e.javaClass.simpleName + " " + (e.message ?: "")
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Dừng chụp màn hình"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                statusView.postDelayed({ updateStatus() }, 500)
            }
        })
        root.addView(Button(this).apply {
            text = "Bật dịch vụ Trợ năng"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        statusView = TextView(this).apply { textSize = 16f }
        root.addView(statusView)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val base = if (CaptureService.isRunning()) "Chụp màn hình: ĐANG BẬT" else "Chụp màn hình: CHƯA BẬT"
        val err = CaptureService.lastError
        statusView.text = if (err.isNotEmpty()) base + "\nLỗi: " + err else base
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1001) return
        if (resultCode == RESULT_OK && data != null) {
            try {
                val i = Intent(this, CaptureService::class.java)
                    .putExtra("code", resultCode)
                    .putExtra("data", data)
                val comp = startForegroundService(i)
                if (comp == null) {
                    statusView.text = "LỖI: chưa khai báo CaptureService trong AndroidManifest.xml (cần thẻ <service android:name=\".CaptureService\" ... foregroundServiceType=\"mediaProjection\" />)"
                    return
                }
                statusView.text = "Đang khởi động chụp màn hình…"
                statusView.postDelayed({ updateStatus() }, 1500)
            } catch (e: Exception) {
                statusView.text = "LỖI khởi động: " + e.javaClass.simpleName + ": " + (e.message ?: "")
            }
        } else {
            statusView.text = "Bạn chưa bấm \"Bắt đầu ngay\" (mã trả về: $resultCode). Nếu không thấy hộp thoại, hãy tắt dịch vụ Trợ năng rồi thử lại."
        }
    }
}
