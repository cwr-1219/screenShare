package com.helper.controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt

class InteractiveImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentBitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val srcRect = RectF()
    private val dstRect = RectF()

    // 记录触摸事件起点
    private var startX = 0f
    private var startY = 0f
    private var startTime = 0L

    // 手势回调接口
    interface OnInteractionListener {
        fun onClick(xPercent: Float, yPercent: Float)
        fun onSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long)
    }

    var interactionListener: OnInteractionListener? = null

    /**
     * 更新显示的屏幕帧
     */
    fun setBitmap(bitmap: Bitmap) {
        currentBitmap = bitmap
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = currentBitmap ?: return

        // 1. 获取 View 和 Bitmap 宽高
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val bmpWidth = bitmap.width.toFloat()
        val bmpHeight = bitmap.height.toFloat()

        // 2. 居中等比缩放计算 (类似 ImageView 的 fitCenter)
        val viewRatio = viewWidth / viewHeight
        val bmpRatio = bmpWidth / bmpHeight

        val drawWidth: Float
        val drawHeight: Float
        if (bmpRatio > viewRatio) {
            drawWidth = viewWidth
            drawHeight = viewWidth / bmpRatio
        } else {
            drawHeight = viewHeight
            drawWidth = viewHeight * bmpRatio
        }

        val left = (viewWidth - drawWidth) / 2
        val top = (viewHeight - drawHeight) / 2

        srcRect.set(0f, 0f, bmpWidth, bmpHeight)
        dstRect.set(left, top, left + drawWidth, top + drawHeight)

        // 3. 绘制
        canvas.drawBitmap(bitmap, null, dstRect, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bitmap = currentBitmap ?: return false

        val x = event.x
        val y = event.y

        // 只有触摸点在 Bitmap 渲染范围内才处理
        if (!dstRect.contains(x, y)) {
            return false
        }

        // 将触摸点的绝对坐标转换为相对于渲染画面 (dstRect) 的百分比
        val relativeXPercent = (x - dstRect.left) / dstRect.width()
        val relativeYPercent = (y - dstRect.top) / dstRect.height()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = relativeXPercent
                startY = relativeYPercent
                startTime = System.currentTimeMillis()
                return true // 拦截后续事件
            }
            MotionEvent.ACTION_UP -> {
                val endTime = System.currentTimeMillis()
                val duration = endTime - startTime
                
                val endX = relativeXPercent
                val endY = relativeYPercent

                // 计算滑动向量距离（相对于屏幕比例）
                val dx = endX - startX
                val dy = endY - startY
                val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

                // 如果移动距离极小（小于屏幕宽高的 2% 比例）且按下时间较短（短于 300ms），判定为点击
                if (distance < 0.02f && duration < 300) {
                    interactionListener?.onClick(startX, startY)
                } else {
                    // 否则判定为滑动事件
                    interactionListener?.onSwipe(startX, startY, endX, endY, duration)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
