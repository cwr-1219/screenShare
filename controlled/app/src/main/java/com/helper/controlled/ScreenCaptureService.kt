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
import android.media.Image
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
    
    private var backgroundThread: android.os.HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var isCapturing = false
    private var lastCaptureTime = 0L
    private val CAPTURE_INTERVAL_MS = 50L // 调优到 50ms 帧间隔，获得每秒约 20 帧的极致流畅体验

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 开启独立的后台子线程进行屏幕像素的抓取和压缩，防止卡顿主线程
        backgroundThread = android.os.HandlerThread("ScreenCaptureBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ScreenCaptureService 启动")
        
        // 1. 启动前台通知，Android 14 (API 34) 必须显式声明 FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 2. 初始化 MediaProjection
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionManager = mpManager

        val resultCode = intent?.getIntExtra("code", -1) ?: -1
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode != -1 && data != null) {
            try {
                mediaProjection = mpManager.getMediaProjection(resultCode, data)
                startCapture()
            } catch (e: Exception) {
                Log.e(TAG, "获取 MediaProjection 失败", e)
                stopSelf()
            }
        } else {
            Log.e(TAG, "未配置 MediaProjection 凭证 (intent 为空或数据无效)")
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
        // 宽度上限设定为 480，高度等比缩放，降低单帧数据大小
        val scale = 480f / screenWidth.coerceAtLeast(1)
        var targetWidth = (screenWidth * scale).toInt()
        var targetHeight = (screenHeight * scale).toInt()

        // 核心兼容性修复：确保物理宽高必须为偶数！
        // 部分国产手机（如 Vivo、OPPO）的 GPU 硬件混合层强制要求虚拟显示源的分辨率必须是偶数对齐，
        // 否则会静默失败不输出任何图像帧。
        if (targetWidth % 2 != 0) {
            targetWidth -= 1
        }
        if (targetHeight % 2 != 0) {
            targetHeight -= 1
        }

        Log.d(TAG, "启动屏幕投影. 原始: ${screenWidth}x${screenHeight}, 兼容性修正后偶数分辨率: ${targetWidth}x${targetHeight}")

        // 实例化 ImageReader。使用 RGBA_8888 格式
        // 提高缓冲区队列长度至 3，彻底避免多线程/高频回调下偶发性锁死
        imageReader = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 3)
        
        // Android 14+ 核心安全兼容性要求：必须在 createVirtualDisplay 之前注册 Callback，否则系统强制抛出 IllegalStateException 报错终止
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                stopCapture()
            }
        }, backgroundHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            targetWidth, targetHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            // 每次回调时，必须首先把最新的一帧取出来，以保证 ImageReader 的缓冲区不积压
            val image = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                Log.e(TAG, "获取 Image 失败", e)
                null
            } ?: return@setOnImageAvailableListener

            val currentTime = System.currentTimeMillis()
            if (currentTime - lastCaptureTime >= CAPTURE_INTERVAL_MS) {
                lastCaptureTime = currentTime
                try {
                    sendImageFrame(image)
                } catch (e: Exception) {
                    Log.e(TAG, "转换并发送屏幕帧数据出错", e)
                } finally {
                    image.close()
                }
            } else {
                // 如果未到发送间隔，则立即关闭释放该帧，从而清空 ImageReader 的内部缓冲区
                image.close()
            }
        }, backgroundHandler)
    }

    private fun sendImageFrame(image: Image) {
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
        cleanBitmap.compress(Bitmap.CompressFormat.JPEG, 50, bos) // 50% 质量，体积更小，传输更快
        val bytes = bos.toByteArray()

        // 释放 Bitmap 内存
        if (cleanBitmap != bitmap) {
            cleanBitmap.recycle()
        }
        bitmap.recycle()

        // 回调发送
        onFrameCapturedListener?.invoke(bytes)
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
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
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
