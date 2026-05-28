package com.helper.controlled

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.helper.controlled.databinding.ActivityMainBinding
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ControlledMainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private var webSocket: WebSocket? = null
    private val okHttpClient = OkHttpClient()
    private var isPaired = false

    // 申请媒体投影权限的启动器
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // 保存凭证
            ScreenCaptureService.mediaProjectionResultCode = result.resultCode
            ScreenCaptureService.mediaProjectionResultData = result.data
            
            // 启动前台截屏服务
            val serviceIntent = Intent(this, ScreenCaptureService::class.java)
            startService(serviceIntent)
            
            binding.tvPermissionProjection.text = "运行中"
            binding.tvPermissionProjection.setTextColor(getColor(android.R.color.holo_green_dark))
            Toast.makeText(this, "投屏服务已启动", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "授权屏幕共享失败", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsState()
    }

    private fun initViews() {
        // 连接服务器按钮
        binding.btnConnect.setOnClickListener {
            val ip = binding.et_server_ip.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "请输入正确的服务器IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            connectToSignalingServer(ip)
        }

        // 去开启无障碍服务权限按钮
        binding.btnGrantAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // 启动投屏按钮
        binding.btnStartProjection.setOnClickListener {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpManager.createScreenCaptureIntent())
        }

        // 绑定截屏服务的图像抓取回调
        ScreenCaptureService.onFrameCapturedListener = { frameBytes ->
            // 如果连接正常且已配对，将画面帧以二进制形式发送
            if (webSocket != null && isPaired) {
                webSocket?.send(frameBytes.toByteString())
            }
        }
    }

    // 检查各项权限的状态并更新 UI
    private fun checkPermissionsState() {
        // 1. 无障碍服务权限检测
        val isAccessibilityEnabled = HelperAccessibilityService.instance != null
        if (isAccessibilityEnabled) {
            binding.tvPermissionAccessibility.text = "已开启"
            binding.tvPermissionAccessibility.setTextColor(getColor(android.R.color.holo_green_dark))
            binding.btnGrantAccessibility.visibility = View.GONE
        } else {
            binding.tvPermissionAccessibility.text = "未开启"
            binding.tvPermissionAccessibility.setTextColor(getColor(android.R.color.holo_red_dark))
            binding.btnGrantAccessibility.visibility = View.VISIBLE
        }
    }

    private fun connectToSignalingServer(ip: String) {
        val request = Request.Builder()
            .url("ws://$ip:3000")
            .build()

        binding.tvStatus.text = "正在连接..."
        binding.tvStatus.setTextColor(getColor(android.R.color.holo_orange_dark))

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread {
                    binding.tvStatus.text = "服务器已连接"
                    binding.tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                }
                
                // 1. 向服务端注册当前角色为被控端 (controlled)
                val registerMsg = JSONObject().apply {
                    put("action", "register_controlled")
                }
                webSocket.send(registerMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val action = json.optString("action")

                    if (action == "register_success") {
                        val code = json.optString("code")
                        runOnUiThread {
                            // 2. 显示生成的 6 位配对码
                            binding.tvPairingCode.text = code
                            binding.tvStatus.text = "等待子女连接..."
                        }
                    } else if (action == "pair_success") {
                        isPaired = true
                        runOnUiThread {
                            binding.tvStatus.text = "已连接并成功配对"
                            binding.tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                        }
                    } else if (action == "peer_disconnected") {
                        isPaired = false
                        runOnUiThread {
                            binding.tvStatus.text = "对方已断开，等待连接..."
                            binding.tvStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
                        }
                    } else if (action == "click") {
                        // 3. 执行远程点击
                        val x = json.optDouble("x", 0.0).toFloat()
                        val y = json.optDouble("y", 0.0).toFloat()
                        HelperAccessibilityService.instance?.performClick(x, y)
                    } else if (action == "swipe") {
                        // 4. 执行远程滑动
                        val startX = json.optDouble("startX", 0.0).toFloat()
                        val startY = json.optDouble("startY", 0.0).toFloat()
                        val endX = json.optDouble("endX", 0.0).toFloat()
                        val endY = json.optDouble("endY", 0.0).toFloat()
                        val duration = json.optLong("duration", 300L)
                        HelperAccessibilityService.instance?.performSwipe(startX, startY, endX, endY, duration)
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "解析 WebSocket 文本消息出错", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    binding.tvStatus.text = "连接断开/失败: ${t.message}"
                    binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    binding.tvPairingCode.text = "------"
                    isPaired = false
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    binding.tvStatus.text = "连接已关闭"
                    binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    binding.tvPairingCode.text = "------"
                    isPaired = false
                }
            }
        })
    }

    override fun onDestroy() {
        webSocket?.close(1000, "Activity销毁")
        super.onDestroy()
    }
}
