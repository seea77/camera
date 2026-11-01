package com.teacher.shotlab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * 构图辅助叠加层：九宫格 / 黄金分割 / 对角线 / 对焦指示。
 * 纯绘制，不持有相机状态。
 */
class CompositionOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class GridMode(val label: String) {
        NONE("关闭"),
        THIRDS("九宫格"),
        GOLDEN("黄金分割"),
        DIAGONAL("对角线")
    }

    var gridMode: GridMode = GridMode.THIRDS
        set(value) {
            field = value
            invalidate()
        }

    /** 左右倾斜角（度），0 表示水平 */
    var roll: Float = 0f
        set(value) {
            if (abs(value - field) > 0.05f) {
                field = value
                invalidate()
            }
        }

    /** 前后俯仰角（度） */
    var pitch: Float = 0f

    private var focusX = -1f
    private var focusY = -1f
    private var focusStart = 0L

    // 辅助线画在实时预览之上，统一用高对比白；对焦圈跟随当前配色的强调色
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
        alpha = 140
    }

    private val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        alpha = 90
    }

    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.attrColor(R.attr.uiAccent)
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
    }

    fun showFocusRing(x: Float, y: Float) {
        focusX = x
        focusY = y
        focusStart = System.currentTimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        when (gridMode) {
            GridMode.THIRDS -> drawThirds(canvas, w, h)
            GridMode.GOLDEN -> drawGolden(canvas, w, h)
            GridMode.DIAGONAL -> drawDiagonal(canvas, w, h)
            GridMode.NONE -> Unit
        }

        drawFocusRing(canvas)
    }

    private fun drawThirds(canvas: Canvas, w: Float, h: Float) {
        for (i in 1..2) {
            val y = h * i / 3f
            canvas.drawLine(0f, y, w, y, linePaint)
            val x = w * i / 3f
            canvas.drawLine(x, 0f, x, h, linePaint)
        }
        // 四个兴趣点加重
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 200
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        for (i in 1..2) for (j in 1..2) {
            canvas.drawCircle(w * i / 3f, h * j / 3f, 6f, dot)
        }
    }

    private fun drawGolden(canvas: Canvas, w: Float, h: Float) {
        val a = 0.382f
        val b = 0.618f
        canvas.drawLine(0f, h * a, w, h * a, linePaint)
        canvas.drawLine(0f, h * b, w, h * b, linePaint)
        canvas.drawLine(w * a, 0f, w * a, h, linePaint)
        canvas.drawLine(w * b, 0f, w * b, h, linePaint)
        canvas.drawLine(0f, h * 0.5f, w, h * 0.5f, dashedPaint)
    }

    private fun drawDiagonal(canvas: Canvas, w: Float, h: Float) {
        canvas.drawLine(0f, 0f, w, h, linePaint)
        canvas.drawLine(w, 0f, 0f, h, linePaint)
        canvas.drawLine(w * 0.5f, 0f, w * 0.5f, h, dashedPaint)
        canvas.drawLine(0f, h * 0.5f, w, h * 0.5f, dashedPaint)
    }

    private fun drawFocusRing(canvas: Canvas) {
        if (focusX < 0f || focusY < 0f) return
        val elapsed = System.currentTimeMillis() - focusStart
        if (elapsed > 2200) return
        val alpha = (255 * (1f - elapsed / 2200f)).toInt().coerceIn(0, 255)
        focusPaint.alpha = alpha
        // 对焦指示：外层方框 + 内部圆形（对焦点）。
        // 点击瞬间框略大并快速回落到常态，给「已响应点击」的即时反馈。
        val half = 80f + (1f - min(elapsed / 400f, 1f)) * 32f
        canvas.drawRect(focusX - half, focusY - half, focusX + half, focusY + half, focusPaint)
        canvas.drawCircle(focusX, focusY, half * 0.5f, focusPaint)
    }
}
