package com.helper.controlled

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.util.Log

class HelperAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "HelperAccessService"
        var instance: HelperAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "无障碍服务已成功连接")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.d(TAG, "无障碍服务已断开")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 暂不需要处理受控端自身的系统事件
    }

    override fun onInterrupt() {
        Log.d(TAG, "无障碍服务被中断")
    }

    /**
     * 模拟点击
     * @param xPercent 相对X轴百分比 (0.0f - 1.0f)
     * @param yPercent 相对Y轴百分比 (0.0f - 1.0f)
     */
    fun performClick(xPercent: Float, yPercent: Float): Boolean {
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels

        val actualX = xPercent * width
        val actualY = yPercent * height

        Log.d(TAG, "执行模拟点击: ($actualX, $actualY)")

        val path = Path()
        path.moveTo(actualX, actualY)

        val gestureBuilder = GestureDescription.Builder()
        // 100ms 延迟，持续 50ms 的轻触
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        
        return dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "点击执行完成")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.d(TAG, "点击执行取消")
            }
        }, null)
    }

    /**
     * 模拟滑动
     */
    fun performSwipe(startXPercent: Float, startYPercent: Float, endXPercent: Float, endYPercent: Float, duration: Long): Boolean {
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels

        val actualStartX = startXPercent * width
        val actualStartY = startYPercent * height
        val actualEndX = endXPercent * width
        val actualEndY = endYPercent * height

        Log.d(TAG, "执行模拟滑动: ($actualStartX, $actualStartY) -> ($actualEndX, $actualEndY)，持续时间: $duration ms")

        val path = Path()
        path.moveTo(actualStartX, actualStartY)
        path.lineTo(actualEndX, actualEndY)

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(100)))

        return dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "滑动执行完成")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.d(TAG, "滑动执行取消")
            }
        }, null)
    }
}
