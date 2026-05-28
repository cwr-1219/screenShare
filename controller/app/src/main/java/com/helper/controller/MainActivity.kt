package com.helper.controller

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.helper.controller.databinding.ActivityMainBinding
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ControllerMainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private var webSocket: WebSocket? = null
    private val okHttpClient = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
    }

    private fun initViews() {
        // 点击配对按钮
        binding.btnPair.setOnClickListener {
            val ip = binding.etServerIp.text.toString().trim()
            val code = binding.etPairCode.text.toString().trim()
            
            if (ip.isEmpty() || code.length != 6) {
                Toast.makeText(this, "请输入正确的服务器IP和6位配对码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            connectAndPair(ip, code)
        }

        // 监听画面交互手势
        binding.ivScreen.interactionListener = object : InteractiveImageView.OnInteractionListener {
            override fun onClick(xPercent: Float, yPercent: Float) {
                // 发送点击事件指令
                if (webSocket != null) {
                    val msg = JSONObject().apply {
                        put("action", "click")
                        put("x", xPercent)
                        put("y", yPercent)
                    }
                    webSocket?.send(msg.toString())
                    Log.d(TAG, "发送点击指令: $msg")
                }
            }

            override fun onSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long) {
                // 发送滑动事件指令
                if (webSocket != null) {
                    val msg = JSONObject().apply {
                        put("action", "swipe")
                        put("startX", startX)
                        put("startY", startY)
                        put("endX", endX)
                        put("endY", endY)
                        put("duration", duration)
                    }
                    webSocket?.send(msg.toString())
                    Log.d(TAG, "发送滑动指令: $msg")
                }
            }
        }
    }

    private fun connectAndPair(ip: String, code: String) {
        val request = Request.Builder()
            .url("ws://$ip:3000")
            .build()

        binding.tvStatus.text = "正在连接中..."
        binding.tvStatus.setTextColor(getColor(android.R.color.holo_orange_dark))

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 1. 发起配对注册请求
                val registerMsg = JSONObject().apply {
                    put("action", "register_controller")
                    put("code", code)
                }
                webSocket.send(registerMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val action = json.optString("action")

                    if (action == "pair_success") {
                        runOnUiThread {
                            binding.tvStatus.text = "配对成功，已成功连接妈妈手机"
                            binding.tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                            binding.tvPlaceholder.visibility = View.GONE
                        }
                    } else if (action == "pair_failed") {
                        val reason = json.optString("reason", "未知原因")
                        runOnUiThread {
                            binding.tvStatus.text = "配对失败: $reason"
                            binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                            Toast.makeText(this@MainActivity, "配对失败: $reason", Toast.LENGTH_LONG).show()
                        }
                        webSocket.close(1000, "配对失败")
                    } else if (action == "peer_disconnected") {
                        runOnUiThread {
                            binding.tvStatus.text = "对方已断开连接"
                            binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                            binding.tvPlaceholder.visibility = View.VISIBLE
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析文本消息失败", e)
                }
            }

            // 2. 接收二进制图片帧数据
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val byteArray = bytes.toByteArray()
                    // 解码为 Bitmap
                    val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                    
                    if (bitmap != null) {
                        runOnUiThread {
                            // 渲染图像
                            binding.ivScreen.setBitmap(bitmap)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解码图片帧失败", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    binding.tvStatus.text = "连接异常: ${t.message}"
                    binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    binding.tvPlaceholder.visibility = View.VISIBLE
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    binding.tvStatus.text = "连接已关闭"
                    binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    binding.tvPlaceholder.visibility = View.VISIBLE
                }
            }
        })
    }

    override fun onDestroy() {
        webSocket?.close(1000, "Activity销毁")
        super.onDestroy()
    }
}
