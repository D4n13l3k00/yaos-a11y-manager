package dev.d4n13l3k00.yaosa11y.feature.update

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import dev.d4n13l3k00.yaosa11y.core.ui.dp
import kotlin.math.abs
import kotlin.math.max

class OtaProgressView(context: Context) : View(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(61, 73, 86)
    }
    private val trackOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(91, 112, 132)
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1).toFloat()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()
    private val glowRect = RectF()
    private val fillRect = RectF()
    private val shimmerMatrix = Matrix()
    private var shimmerShader: LinearGradient? = null
    private var displayedProgress = 0f
    private var shimmerPosition = -0.25f
    private var startColor = BLUE_START
    private var endColor = BLUE_END
    private var progressAnimator: ValueAnimator? = null
    private var toneAnimator: ValueAnimator? = null
    private var shimmerAnimator: ValueAnimator? = null
    private var downloading = false
    private var animationsEnabled = true

    init {
        minimumHeight = context.dp(20)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(context.dp(20), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val barWidth = width.toFloat()
        val barHeight = height.toFloat()
        val radius = barHeight / 2f
        trackRect.set(0f, 0f, barWidth, barHeight)
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)
        canvas.drawRoundRect(
            context.dp(1).toFloat(),
            context.dp(1).toFloat(),
            barWidth - context.dp(1),
            barHeight - context.dp(1),
            radius,
            radius,
            trackOutlinePaint,
        )
        if (displayedProgress <= 0f) return

        val fillWidth = max(barHeight, barWidth * displayedProgress.coerceIn(0f, 1f))
        glowPaint.color = withAlpha(endColor, 48)
        glowRect.set(0f, context.dp(1).toFloat(), fillWidth, barHeight - context.dp(1))
        canvas.drawRoundRect(
            glowRect,
            radius,
            radius,
            glowPaint,
        )

        canvas.save()
        canvas.clipRect(0f, 0f, fillWidth, barHeight)
        fillRect.set(0f, 0f, fillWidth, barHeight)
        canvas.drawRoundRect(
            fillRect,
            radius,
            radius,
            fillPaint,
        )
        if (downloading) {
            shimmerMatrix.setTranslate(barWidth * shimmerPosition, 0f)
            shimmerShader?.setLocalMatrix(shimmerMatrix)
            canvas.drawRect(0f, 0f, fillWidth, barHeight, shimmerPaint)
        }
        canvas.restore()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateFillShader()
        val halfWidth = width * 0.11f
        shimmerShader = LinearGradient(
            -halfWidth,
            0f,
            halfWidth,
            0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(130, 255, 255, 255),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        ).also { shimmerPaint.shader = it }
    }

    fun reset() {
        downloading = false
        cancelAnimators()
        displayedProgress = 0f
        startColor = BLUE_START
        endColor = BLUE_END
        invalidate()
    }

    fun startDownload() {
        downloading = true
        displayedProgress = max(displayedProgress, 0.02f)
        animateTone(BLUE_START, BLUE_END)
        startShimmer()
        invalidate()
    }

    fun setProgressAnimated(progress: Float) {
        val target = progress.coerceIn(0f, 1f)
        progressAnimator?.cancel()
        progressAnimator = ValueAnimator.ofFloat(displayedProgress, target).apply {
            duration = (220 + abs(target - displayedProgress) * 420).toLong()
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                displayedProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun finishSuccess() {
        downloading = false
        shimmerAnimator?.cancel()
        setProgressAnimated(1f)
        animateTone(GREEN_START, GREEN_END)
    }

    fun finishError() {
        downloading = false
        shimmerAnimator?.cancel()
        animateTone(RED_START, RED_END)
    }

    fun onHostPaused() {
        animationsEnabled = false
        shimmerAnimator?.cancel()
    }

    fun onHostResumed() {
        animationsEnabled = true
        if (downloading) startShimmer()
    }

    override fun onDetachedFromWindow() {
        cancelAnimators()
        super.onDetachedFromWindow()
    }

    private fun animateTone(targetStart: Int, targetEnd: Int) {
        toneAnimator?.cancel()
        val initialStart = startColor
        val initialEnd = endColor
        val evaluator = ArgbEvaluator()
        toneAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedFraction
                startColor = evaluator.evaluate(fraction, initialStart, targetStart) as Int
                endColor = evaluator.evaluate(fraction, initialEnd, targetEnd) as Int
                updateFillShader()
                invalidate()
            }
            start()
        }
    }

    private fun startShimmer() {
        shimmerAnimator?.cancel()
        if (!animationsEnabled || !downloading) return
        shimmerAnimator = ValueAnimator.ofFloat(-0.25f, 1.25f).apply {
            duration = 1_350
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                shimmerPosition = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun cancelAnimators() {
        progressAnimator?.cancel()
        toneAnimator?.cancel()
        shimmerAnimator?.cancel()
        progressAnimator = null
        toneAnimator = null
        shimmerAnimator = null
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun updateFillShader() {
        if (width <= 0) return
        fillPaint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            0f,
            startColor,
            endColor,
            Shader.TileMode.CLAMP,
        )
    }

    companion object {
        private val BLUE_START = Color.rgb(74, 157, 255)
        private val BLUE_END = Color.rgb(99, 222, 255)
        private val GREEN_START = Color.rgb(79, 191, 137)
        private val GREEN_END = Color.rgb(148, 239, 179)
        private val RED_START = Color.rgb(229, 84, 104)
        private val RED_END = Color.rgb(255, 140, 112)
    }
}
