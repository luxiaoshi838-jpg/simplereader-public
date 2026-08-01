package com.simplereader.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * A single flow page surface for overlap, horizontal scroll, vertical scroll and fade.
 * The same page snapshots stay bound while the rendering effect/layout changes.
 * Simulation also consumes the same previous/current/next snapshots; it never owns
 * chapter state or reading progress.
 */
class PagedReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    enum class TurnMode { OVERLAP, SIMULATE, SLIDE, FADE }

    data class Style(
        val textSizePx: Float,
        val textColor: Int,
        val horizontalPaddingPx: Int,
        val topPaddingPx: Int,
        val bottomPaddingPx: Int,
        val lineSpacingMultiplier: Float,
        val typeface: Typeface? = Typeface.DEFAULT,
        val backgroundFactory: () -> Drawable
    )

    var turnMode: TurnMode = TurnMode.OVERLAP
        private set

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
            intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(88, 0, 0, 0))
        )
    }

    private var previousPage: ReaderPageSnapshot? = null
    private var currentPage: ReaderPageSnapshot? = null
    private var nextPage: ReaderPageSnapshot? = null
    private var style: Style? = null
    private var activeAnimator: ValueAnimator? = null
    private var animating = false
    private var dragging = false
    private var dragDirection = 0
    private var activeDirection = 0
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

    fun setTurnMode(mode: TurnMode) {
        if (turnMode == mode) return
        cancelActiveAnimation(reset = true)
        turnMode = mode
        resetTransforms()
    }

    /** Cancel any page gesture/animation before a renderer or chapter transaction. */
    fun cancelNavigation() {
        longPressHandler.removeCallbacks(longPressRunnable)
        velocityTracker?.recycle()
        velocityTracker = null
        cancelActiveAnimation(reset = true)
    }

    fun configure(style: Style) {
        this.style = style
        listOf(previousView, currentView, nextView).forEach { page ->
            page.setTextSize(TypedValue.COMPLEX_UNIT_PX, style.textSizePx)
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
        cancelActiveAnimation(reset = false)
        previousPage = previous
        currentPage = current
        nextPage = next
        previousView.text = previous?.content ?: ""
        currentView.text = current.content
        nextView.text = next?.content ?: ""
        resetTransforms()
    }

    /** Update only the prefetched sides; the visible page is not rebound or flashed. */
    fun updateAdjacent(
        previous: ReaderPageSnapshot?,
        next: ReaderPageSnapshot?
    ) {
        previousPage = previous
        nextPage = next
        previousView.text = previous?.content ?: ""
        nextView.text = next?.content ?: ""
        if (!animating && !dragging) resetTransforms()
    }

    fun currentSnapshot(): ReaderPageSnapshot? = currentPage

    fun turn(direction: Int): Boolean {
        if (isReaderChromeVisible()) {
            cancelNavigation()
            return false
        }
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
        if (isReaderChromeVisible()) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelNavigation()
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val tapSlop = dp(16).toFloat()
                    val movedX = kotlin.math.abs(event.x - downX)
                    val movedY = kotlin.math.abs(event.y - downY)
                    if (
                        movedX <= tapSlop &&
                        movedY <= tapSlop &&
                        event.x in width * 0.33f..width * 0.67f &&
                        event.y in height * 0.18f..height * 0.82f
                    ) {
                        onCenterTap?.invoke()
                    }
                }
            }
            return true
        }
        if (animating) return true
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
                velocityTracker?.addMovement(event)
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) > dp(10) || abs(dy) > dp(10)) {
                    longPressHandler.removeCallbacks(longPressRunnable)
                }
                if (!dragging) {
                    val primary = dx
                    val secondary = dy
                    if (abs(primary) > dp(10) && abs(primary) > abs(secondary) * 1.15f) {
                        dragDirection = if (primary < 0f) 1 else -1
                        val incoming = if (dragDirection > 0) nextPage else previousPage
                        if (incoming == null) {
                            dragDirection = 0
                            return true
                        }
                        dragging = true
                    }
                }
                if (longPressTriggered) {
                    longPressTriggered = false
                    return true
                }
                if (dragging) {
                    val distance = abs(dx)
                    dragProgress = (distance / width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                    applyProgress(dragDirection, dragProgress)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.addMovement(event)
                longPressHandler.removeCallbacks(longPressRunnable)
                velocityTracker?.computeCurrentVelocity(1000)
                val velocity = velocityTracker?.xVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null

                if (longPressTriggered) {
                    longPressTriggered = false
                    return true
                }
                if (dragging) {
                    val forwardVelocity = if (dragDirection > 0) -velocity else velocity
                    val commit = event.actionMasked == MotionEvent.ACTION_UP &&
                        (dragProgress >= 0.24f || forwardVelocity > 720f)
                    dragging = false
                    if (commit) animateTurn(dragDirection, dragProgress) else animateReset(dragProgress)
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
        if (animating) return
        activeDirection = direction
        dragDirection = direction
        animating = true
        prepareForDirection(direction)
        startProgressAnimator(
            from = fromProgress.coerceIn(0f, 1f),
            to = 1f,
            duration = (260L * (1f - fromProgress).coerceIn(0.18f, 1f)).toLong().coerceAtLeast(90L),
            onComplete = { finishTurn(direction) }
        )
    }

    private fun animateReset(fromProgress: Float) {
        animating = true
        startProgressAnimator(
            from = fromProgress.coerceIn(0f, 1f),
            to = 0f,
            duration = 150L,
            onComplete = { resetTransforms() }
        )
    }

    private fun startProgressAnimator(
        from: Float,
        to: Float,
        duration: Long,
        onComplete: () -> Unit
    ) {
        activeAnimator?.cancel()
        activeAnimator = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val direction = activeDirection.takeIf { it != 0 }
                    ?: dragDirection.takeIf { it != 0 }
                    ?: 1
                applyProgress(direction, animator.animatedValue as Float)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (activeAnimator === animation) activeAnimator = null
                    if (cancelled) {
                        resetTransforms()
                    } else {
                        onComplete()
                    }
                }
            })
            start()
        }
    }

    private fun finishTurn(direction: Int) {
        edgeShadow.visibility = View.GONE
        val before = currentPage
        animating = false
        dragProgress = 0f
        onTurnCommitted?.invoke(direction)
        activeDirection = 0
        dragDirection = 0
        // The controller normally rebinds synchronously. If it could not commit,
        // restore the old visible page instead of leaving a transparent/translated frame.
        if (currentPage === before) resetTransforms()
    }

    private fun applyProgress(direction: Int, progress: Float) {
        prepareForDirection(direction)
        val incoming = if (direction > 0) nextView else previousView
        val outgoing = currentView
        when (turnMode) {
            TurnMode.OVERLAP -> {
                incoming.translationX = if (direction > 0) width * (1f - progress) else -width * (1f - progress)
                incoming.translationY = 0f
                incoming.alpha = 1f
                outgoing.alpha = 1f
            }

            TurnMode.SLIDE -> {
                outgoing.translationX = if (direction > 0) -width * progress else width * progress
                incoming.translationX = if (direction > 0) width * (1f - progress) else -width * (1f - progress)
                outgoing.translationY = 0f
                incoming.translationY = 0f
            }


            TurnMode.FADE -> {
                incoming.translationX = 0f
                incoming.translationY = 0f
                outgoing.translationX = 0f
                outgoing.translationY = 0f
                outgoing.alpha = 1f - progress
                incoming.alpha = progress
            }

            TurnMode.SIMULATE -> {
                edgeShadow.visibility = View.VISIBLE
                outgoing.pivotX = if (direction > 0) 0f else width.toFloat()
                outgoing.rotationY = if (direction > 0) -72f * progress else 72f * progress
                outgoing.translationX = if (direction > 0) -width * 0.18f * progress else width * 0.18f * progress
                outgoing.alpha = 1f - progress * 0.82f
                incoming.translationX = 0f
                incoming.translationY = 0f
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
        when (turnMode) {
            TurnMode.SIMULATE -> {
                incoming.bringToFront()
                outgoing.bringToFront()
                edgeShadow.bringToFront()
            }

            else -> {
                outgoing.bringToFront()
                incoming.bringToFront()
                edgeShadow.bringToFront()
            }
        }
    }

    private fun cancelActiveAnimation(reset: Boolean) {
        val animator = activeAnimator
        activeAnimator = null
        animator?.removeAllUpdateListeners()
        animator?.removeAllListeners()
        animator?.cancel()
        animating = false
        dragging = false
        activeDirection = 0
        dragDirection = 0
        if (reset) resetTransforms()
    }

    private fun resetTransforms() {
        listOf(previousView, currentView, nextView).forEach {
            it.animate().cancel()
            it.alpha = 1f
            it.rotationY = 0f
            it.rotationX = 0f
            it.scaleX = 1f
            it.scaleY = 1f
            it.translationX = 0f
            it.translationY = 0f
            it.cameraDistance = resources.displayMetrics.density * 9000f
        }
        when (turnMode) {
            TurnMode.FADE, TurnMode.SIMULATE -> Unit
            else -> {
                previousView.translationX = -width.toFloat()
                nextView.translationX = width.toFloat()
            }
        }
        previousView.visibility = if (previousPage == null) View.INVISIBLE else View.VISIBLE
        nextView.visibility = if (nextPage == null) View.INVISIBLE else View.VISIBLE
        currentView.visibility = View.VISIBLE
        currentView.bringToFront()
        edgeShadow.visibility = View.GONE
        animating = false
        dragging = false
        activeDirection = 0
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
        breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        setTextColor(Color.rgb(59, 52, 40))
        textSize = 20f
    }

    private fun matchParentParams() = LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun isReaderChromeVisible(): Boolean =
        rootView.findViewById<View>(R.id.readerControls)?.visibility == View.VISIBLE

    private fun dp(value: Int): Float = value * resources.displayMetrics.density
}
