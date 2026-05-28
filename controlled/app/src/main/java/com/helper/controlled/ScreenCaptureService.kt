package com.helper.controlled

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ScreenCaptureChannel"

        // 供 Activity 传入 MediaProjection 凭证
        var mediaProjectionResultCode: Int = 0
        var mediaProjectionResultData: Intent? = null
        
        // 发送压缩屏幕帧的回调
        var onFrameCapturedListener: ((ByteArray) -> Unit)? = null
    }

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private var isCapturing = false
    private var lastCaptureTime = 0L
    private val CAPTURE_INTERVAL_MS = 150L // 限制帧率，大约每秒 6-7 帧，局域网够用且极大省电

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ScreenCaptureService 启动")
        
        // 1. 启动前台通知，这是 Android 的安全限制，保证录屏时用户完全知晓
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // 2. 初始化 MediaProjection
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionManager = mpManager

        val data = mediaProjectionResultData
        if (data != null) {
            try {
                mediaProjection = mpManager.getMediaProjection(mediaProjectionResultCode, data)
                startCapture()
            } catch (e: Exception) {
                Log.e(TAG, "获取 MediaProjection 失败", e)
                stopSelf()
            }
        } else {
            Log.e(TAG, "未配置 MediaProjection 凭证")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startCapture() {
        if (isCapturing) return
        isCapturing = true

        val dm = resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val density = dm.densityDpi

        // 局域网传输画面，将分辨率等比例缩小，减少传输数据量，提高流畅度
        // 宽度上限设定为 540，高度等比缩放
        val scale = 540f / screenWidth.coerceAtLeast(1)
        val targetWidth = (screenWidth * scale).toInt()
        val targetHeight = (screenHeight * scale).toInt()

        Log.d(TAG, "启动屏幕投影. 原始: ${screenWidth}x${screenHeight}, 缩放后: ${targetWidth}x${targetHeight}")

        // 实例化 ImageReader。使用 RGBA_8888 格式
        imageReader = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            targetWidth, targetHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastCaptureTime >= CAPTURE_INTERVAL_MS) {
                lastCaptureTime = currentTime
                acquireLatestAndSend(reader)
            } else {
                // 如果还不到采集间隔，直接丢弃该帧释放缓冲区
                val image = reader.acquireLatestImage()
                image?.close()
            }
        }, handler)
    }

    private fun acquireLatestAndSend(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        try {
            val width = image.width
            val height = image.height
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            // 转换成 Bitmap
            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // 如果有内边距填充，需裁剪出实际有效图像
            val cleanBitmap = if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, width, height)
            } else {
                bitmap
            }

            // 压缩为 JPEG 字节数组
            val bos = ByteArrayOutputStream()
            cleanBitmap.compress(Bitmap.CompressFormat.JPEG, 60, bos) // 60% 质量在移动端完全足够，且大小仅 30-50KB
            val bytes = bos.toByteArray()

            // 释放 Bitmap 内存
            if (cleanBitmap != bitmap) {
                cleanBitmap.recycle()
            }
            bitmap.recycle()

            // 回调发送
            onFrameCapturedListener?.invoke(bytes)

        } catch (e: Exception) {
            Log.e(TAG, "截取屏幕数据转换出错", e)
        } finally {
            image.close()
        }
    }

    private fun stopCapture() {
        isCapturing = false
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        Log.d(TAG, "停止屏幕捕获")
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "屏幕共享服务通知",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("远程协助投屏中")
            .setContentText("您的子女正在远程查看屏幕以协助您...")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }
}
