package com.vanmypq.autoanswer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

// Giữ quyền chụp màn hình (MediaProjection) để gửi ảnh cho AI đọc công thức/hình vẽ.
class CaptureService : Service() {
    companion object {
        @Volatile private var reader: ImageReader? = null
        @Volatile private var cached: String? = null
        @Volatile private var w = 0
        @Volatile private var h = 0
        @Volatile var lastError: String = ""

        fun isRunning(): Boolean = reader != null

        // Trả về ảnh màn hình hiện tại dạng JPEG base64, hoặc null nếu chưa cấp quyền.
        @Synchronized
        fun grab(maxSide: Int = 0): String? {
            val r = reader ?: return null
            var image: android.media.Image? = null
            try {
                image = r.acquireLatestImage()
            } catch (e: Exception) {
            }
            if (image != null) {
                try {
                    val plane = image.planes[0]
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * w
                    val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(plane.buffer)
                    val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                    val bos = ByteArrayOutputStream()
                    var out = cropped
                    if (maxSide > 0 && maxOf(w, h) > maxSide) {
                        val sc = maxSide.toFloat() / maxOf(w, h)
                        out = Bitmap.createScaledBitmap(cropped, (w * sc).toInt(), (h * sc).toInt(), true)
                    }
                    out.compress(Bitmap.CompressFormat.JPEG, if (maxSide > 0) 55 else 65, bos)
                    cached = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                } catch (e: Exception) {
                } finally {
                    image.close()
                }
            }
            return cached
        }
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            lastError = ""
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("cap", "Chụp màn hình", NotificationManager.IMPORTANCE_LOW)
            )
            val n = Notification.Builder(this, "cap")
                .setContentTitle("Auto Trắc Nghiệm")
                .setContentText("Đang cho phép chụp màn hình để AI đọc công thức")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

            val code = intent?.getIntExtra("code", 0) ?: 0
            val data: Intent? = intent?.getParcelableExtra("data")
            if (data == null) {
                lastError = "Không nhận được dữ liệu cấp quyền từ hệ thống"
                release()
                return START_NOT_STICKY
            }
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(code, data)
            projection = mp
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    release()
                }
            }, Handler(Looper.getMainLooper()))

            val real = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(real)
            val scale = 1280f / maxOf(real.widthPixels, real.heightPixels)
            w = (real.widthPixels * scale).toInt()
            h = (real.heightPixels * scale).toInt()
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            display = mp.createVirtualDisplay(
                "autoanswer-cap", w, h, real.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
            )
            reader = r
        } catch (e: Throwable) {
            lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
            release()
        }
        return START_NOT_STICKY
    }

    private fun release() {
        try {
            display?.release()
        } catch (e: Exception) {
        }
        display = null
        try {
            reader?.close()
        } catch (e: Exception) {
        }
        reader = null
        cached = null
        try {
            projection?.stop()
        } catch (e: Exception) {
        }
        projection = null
        stopSelf()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }
}
