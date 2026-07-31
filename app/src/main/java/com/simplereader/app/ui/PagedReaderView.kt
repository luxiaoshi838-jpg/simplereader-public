package com.simplereader.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Reusable three-page reader surface. Page preparation and page-turn animation
 * are deliberately separate: the view never changes chapters or reading
 * positions; it only animates already prepared previous/current/next pages.
 */
class PagedReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    enum class TurnMode { OVERLAP, SIMULATE, SLIDE, FADE }

    data class Style(
        val textSizeSp: Float,
        val textColor: Int,
        val horizontalPaddingPx: Int,
        val topPaddingPx: Int,
        val bottomPaddingPx: Int,
        val lineSpacingMultiplier: Float,
        val typeface: Typeface? = Typeface.DEFAULT,
        val backgroundFactory: () -> Drawable
    )

    var turnMode: TurnMode = TurnMode.OVERLAP
    var onTurnCommitted: ((direction: Int) -> Unit)? = null
    var onBoundaryTurn: ((direction: Int) -> Unit)? = null
    var onCenterTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    private val previousView = createPageView()
    private val currentView = createPageView()
    private val nextView = createPageView()
    private val edgeShadow = View(context).apply {
        visibility = View.GONE
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(95, 0, 0, 0))
        )
    }

    private var previousPage: ReaderPageSnapshot? = null
    private var currentPage: ReaderPageSnapshot? = null
    private var nextPage: ReaderPageSnapshot? = null
    private var style: Style? = null
    private var animating = false
    private var dragging = false
    private var dragDirection = 0
    private var downX = 0f
    private var downY = 0f
    private var dragProgress = 0f
    private var velocityTracker: VelocityTracker? = null
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressTriggered = false
    private val longPressRunnable = Runnable {
        if (!dragging && !animating) {
            longPressTriggered = true
            onLongPress?.invoke()
        }
    }

    init {
        isClickable = true
        isFocusable = true
        clipChildren = false
        addView(previousView, matchParentParams())
        addView(nextView, matchParentParams())
        addView(currentView, matchParentParams())
        addView(edgeShadow, LayoutParams(dp(42).toInt(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.END
        })
    }

    fun configure(style: Style) {
        this.style = style
        listOf(previousView, currentView, nextView).forEach { page ->
            page.textSize = style.textSizeSp
            page.setTextColor(style.textColor)
            page.typeface = style.typeface
            page.setLineSpacing(0f, style.lineSpacingMultiplier)
            page.setPadding(
                style.horizontalPaddingPx,
                style.topPaddingPx,
                style.horizontalPaddingPx,
                style.bottomPaddingPx
            )
            page.background = style.backgroundFactory()
        }
        invalidate()
    }

    fun bind(
        previous: ReaderPageSnapshot?,
        current: ReaderPageSnapshot,
        next: ReaderPageSnapshot?
    ) {
        previousPage = previous
        currentPage = current
        nextPage = next
        previousView.text = previous?.content ?: ""
        currentView.text = current.content
        nextView.text = next?.content ?: ""
        resetTransforms()
    }

    fun currentSnapshot(): ReaderPageSnapshot? = currentPage

    fun turn(direction: Int): Boolean {
        if (animating || dragging || direction == 0) return false
        val incoming = if (direction > 0) nextPage else previousPage
        if (incoming == null) {
            onBoundaryTurn?.invoke(direction)
            return false
        }
        animateTurn(direction, fromProgress = 0f)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (animating) return true
        velocityTracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                dragProgress = 0f
                dragDirection = 0
                dragging = false
                longPressTriggered = false
                longPressHandler.removeCallbacks(longPressRunnable)
                longPressHandler.postDelayed(longPressRunnable, 520L)
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) > dp(10) || abs(dy) > dp(10)) {
                    longPressHandler.removeCallbacks(longPressRunnable)
                }
                if (!dragging && abs(dx) > dp(10) && abs(dx) > abs(dy) * 1.15f) {
                    dragDirection = if (dx < 0f) 1 else -1
                    val incoming = if (dragDirection > 0) nextPage else previousPage
                    if (incoming == null) {
                        dragDirection = 0
                        return true
                    }
                    dragging = true
                }
                if (longPressTriggered) {
                    longPressTriggered = false
                    return true
                }
                if (dragging) {
                    dragProgress = (abs(dx) / width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                    applyProgress(dragDirection, dragProgress)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                velocityTracker?.computeCurrentVelocity(1000)
                val velocityX = velocityTracker?.xVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null
                if (longPressTriggered) {
                    longPressTriggered = false
                    return true
                }
                if (dragging) {
                    val forwardVelocity = if (dragDirection > 0) -velocityX else velocityX
                    val commit = event.actionMasked == MotionEvent.ACTION_UP &&
                        (dragProgress >= 0.24f || forwardVelocity > 720f)
                    if (commit) {
                        animateTurn(dragDirection, dragProgress)
                    } else {
                        animateReset()
                    }
                    dragging = false
                    return true
                }

                if (event.actionMasked == MotionEvent.ACTION_UP &&
                    abs(event.x - downX) < dp(12) && abs(event.y - downY) < dp(12)
                ) {
                    when {
                        event.x < width * 0.33f -> turn(-1)
                        event.x > width * 0.67f -> turn(1)
                        else -> onCenterTap?.invoke()
                    }
                }
                return true
            }
        }
        return true
    }

    private fun animateTurn(direction: Int, fromProgress: Float) {
        val incoming = if (direction > 0) nextView else previousView
        val outgoing = currentView
        animating = true
        prepareForDirection(direction)
        if (fromProgress > 0f) applyProgress(direction, fromProgress)
        val remaining = (1f - fromProgress).coerceIn(0.18f, 1f)
        val duration = (260L * remaining).toLong().coerceAtLeast(90L)

        when (turnMode) {
            TurnMode.OVERLAP -> {
                incoming.animate().translationX(0f).setDuration(duration).start()
                outgoing.animate().alpha(0.94f).setDuration(duration).withEndAction {
                    finishTurn(direction)
                }.start()
            }
            TurnMode.SLIDE -> {
                incoming.animate().translationX(0f).setDuration(duration).start()
                outgoing.animate()
                    .translationX(if (direction > 0) -width.toFloat() else width.toFloat())
                    .setDuration(duration)
                    .withEndAction { finishTurn(direction) }
                    .start()
            }
            TurnMode.FADE -> {
                incoming.animate().alpha(1f).setDuration(duration).start()
                outgoing.animate().alpha(0f).setDuration(duration).withEndAction {
                    finishTurn(direction)
                }.start()
            }
            TurnMode.SIMULATE -> {
                edgeShadow.visibility = View.VISIBLE
                incoming.animate().alpha(1f).scaleX(1f).setDuration(duration).start()
                outgoing.animate()
                    .rotationY(if (direction > 0) -72f else 72f)
                    .translationX(if (direction > 0) -width * 0.18f else width * 0.18f)
                    .alpha(0.18f)
                    .setDuration(duration)
                    .withEndAction { finishTurn(direction) }
                    .start()
            }
        }
    }

    private fun animateReset() {
        animating = true
        previousView.animate().translationX(-width.toFloat()).alpha(1f).rotationY(0f).setDuration(150L).start()
        nextView.animate().translationX(width.toFloat()).alpha(1f).rotationY(0f).setDuration(150L).start()
        currentView.animate().translationX(0f).alpha(1f).rotationY(0f).scaleX(1f)
            .setDuration(150L).withEndAction {
                animating = false
                resetTransforms()
            }.start()
    }

    private fun finishTurn(direction: Int) {
        animating = false
        edgeShadow.visibility = View.GONE
        onTurnCommitted?.invoke(direction)
    }

    private fun applyProgress(direction: Int, progress: Float) {
        prepareForDirection(direction)
        val incoming = if (direction > 0) nextView else previousView
        val outgoing = currentView
        when (turnMode) {
            TurnMode.OVERLAP -> {
                incoming.translationX = if (direction > 0) width * (1f - progress) else -width * (1f - progress)
                outgoing.alpha = 1f - progress * 0.06f
            }
            TurnMode.SLIDE -> {
                outgoing.translationX = if (direction > 0) -width * progress else width * progress
                incoming.translationX = if (direction > 0) width * (1f - progress) else -width * (1f - progress)
            }
            TurnMode.FADE -> {
                outgoing.alpha = 1f - progress
                incoming.alpha = progress
            }
            TurnMode.SIMULATE -> {
                edgeShadow.visibility = View.VISIBLE
                outgoing.pivotX = if (direction > 0) 0f else width.toFloat()
                outgoing.rotationY = if (direction > 0) -72f * progress else 72f * progress
                outgoing.translationX = if (direction > 0) -width * 0.18f * progress else width * 0.18f * progress
                outgoing.alpha = 1f - progress * 0.82f
                incoming.alpha = 0.55f + progress * 0.45f
                incoming.scaleX = 0.96f + progress * 0.04f
            }
        }
    }

    private fun prepareForDirection(direction: Int) {
        previousView.visibility = if (previousPage == null) View.INVISIBLE else View.VISIBLE
        nextView.visibility = if (nextPage == null) View.INVISIBLE else View.VISIBLE
        val incoming = if (direction > 0) nextView else previousView
        val outgoing = currentView
        incoming.bringToFront()
        if (turnMode == TurnMode.SIMULATE || turnMode == TurnMode.SLIDE || turnMode == TurnMode.FADE) {
            outgoing.bringToFront()
            if (turnMode != TurnMode.SIMULATE) incoming.bringToFront()
        }
        edgeShadow.bringToFront()
        if (turnMode == TurnMode.SIMULATE) {
            incoming.bringToFront()
            outgoing.bringToFront()
            edgeShadow.bringToFront()
        }
    }

    private fun resetTransforms() {
        listOf(previousView, currentView, nextView).forEach {
            it.animate().cancel()
            it.alpha = 1f
            it.rotationY = 0f
            it.rotationX = 0f
            it.scaleX = 1f
            it.scaleY = 1f
            it.translationY = 0f
            it.cameraDistance = resources.displayMetrics.density * 9000f
        }
        previousView.translationX = -width.toFloat()
        currentView.translationX = 0f
        nextView.translationX = width.toFloat()
        previousView.visibility = if (previousPage == null) View.INVISIBLE else View.VISIBLE
        nextView.visibility = if (nextPage == null) View.INVISIBLE else View.VISIBLE
        currentView.visibility = View.VISIBLE
        currentView.bringToFront()
        edgeShadow.visibility = View.GONE
        animating = false
        dragging = false
        dragDirection = 0
        dragProgress = 0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetTransforms()
    }

    private fun createPageView(): TextView = TextView(context).apply {
        gravity = Gravity.TOP or Gravity.START
        setTextIsSelectable(false)
        isLongClickable = false
        isClickable = false
        includeFontPadding = true
        setTextColor(Color.rgb(59, 52, 40))
        textSize = 20f
    }

    private fun matchParentParams() = LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun dp(value: Int): Float = value * resources.displayMetrics.density
}
