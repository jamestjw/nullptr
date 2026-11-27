package com.nullptr.trackpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

class TouchpadView(context: Context, private val send: (InputEvent) -> Unit) : View(context) {
    var sensitivity = 1f
    var invertScroll = false

    private enum class Phase { TAP, MOVE, DRAG }

    private sealed interface Gesture {
        data object Idle : Gesture
        data class Pointer(
            val id: Int,
            val origin: Point,
            var last: Point = origin,
            var phase: Phase = Phase.TAP,
        ) : Gesture
        data class TwoFingers(
            val firstId: Int,
            val secondId: Int,
            val firstOrigin: Point,
            val secondOrigin: Point,
            var last: Point,
            var tap: Boolean = true,
        ) : Gesture
        data class FinishingTap(val id: Int, val origin: Point) : Gesture
        data object Suppressed : Gesture
    }

    private data class Point(val x: Float, val y: Float) {
        fun movedFrom(origin: Point, slop: Float): Boolean {
            val dx = x - origin.x
            val dy = y - origin.y
            return dx * dx + dy * dy > slop * slop
        }
    }

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var gesture: Gesture = Gesture.Idle
    private var pendingX = 0f
    private var pendingY = 0f
    private val flushFrame = Runnable { flush() }
    private val beginDrag = Runnable {
        val pointer = gesture as? Gesture.Pointer ?: return@Runnable
        if (pointer.phase == Phase.TAP) {
            pointer.phase = Phase.DRAG
            send(InputEvent.Button(MouseButton.LEFT, true))
        }
    }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Trackpad. Slide to move. Tap to click. Hold to drag. Two fingers to scroll or right-click."
    }

    fun reset() {
        endGesture()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                endGesture()
                gesture = Gesture.Pointer(event.getPointerId(0), event.point(0))
                postDelayed(beginDrag, 420)
            }
            MotionEvent.ACTION_POINTER_DOWN -> startTwoFingers(event)
            MotionEvent.ACTION_MOVE -> move(event)
            MotionEvent.ACTION_POINTER_UP -> liftOneFinger(event)
            MotionEvent.ACTION_UP -> {
                when (val current = gesture) {
                    is Gesture.Pointer -> {
                        move(event)
                        if (current.phase == Phase.TAP && event.eventTime - event.downTime < 420) {
                            performClick()
                        }
                    }
                    is Gesture.FinishingTap -> {
                        if (event.getPointerId(0) == current.id &&
                            !event.point(0).movedFrom(current.origin, slop) && isTwoFingerTap(event)
                        ) {
                            click(MouseButton.RIGHT)
                        }
                    }
                    else -> Unit
                }
                endGesture()
            }
            MotionEvent.ACTION_CANCEL -> endGesture()
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        if (!isEnabled) return false
        super.performClick()
        click(MouseButton.LEFT)
        return true
    }

    private fun startTwoFingers(event: MotionEvent) {
        val pointer = gesture as? Gesture.Pointer
        if (event.pointerCount != 2 || pointer == null) {
            endGesture()
            gesture = Gesture.Suppressed
            return
        }
        val firstIndex = event.findPointerIndex(pointer.id)
        val secondIndex = event.actionIndex
        val first = event.point(firstIndex)
        val second = event.point(secondIndex)
        val tap = pointer.phase == Phase.TAP && !first.movedFrom(pointer.origin, slop)
        endGesture()
        gesture = Gesture.TwoFingers(
            pointer.id, event.getPointerId(secondIndex), first, second, midpoint(first, second), tap,
        )
    }

    private fun move(event: MotionEvent) {
        when (val current = gesture) {
            is Gesture.Pointer -> {
                val index = event.findPointerIndex(current.id)
                if (index == -1) return
                val point = event.point(index)
                if (current.phase == Phase.TAP) {
                    if (!point.movedFrom(current.origin, slop)) return
                    removeCallbacks(beginDrag)
                    current.phase = Phase.MOVE
                }
                accumulate(point, current.last, sensitivity / density)
                current.last = point
            }
            is Gesture.TwoFingers -> {
                val firstIndex = event.findPointerIndex(current.firstId)
                val secondIndex = event.findPointerIndex(current.secondId)
                if (firstIndex == -1 || secondIndex == -1) return
                val first = event.point(firstIndex)
                val second = event.point(secondIndex)
                if (current.tap) {
                    if (!first.movedFrom(current.firstOrigin, slop) &&
                        !second.movedFrom(current.secondOrigin, slop)
                    ) return
                    current.tap = false
                }
                val point = midpoint(first, second)
                val direction = if (invertScroll) -1f else 1f
                accumulate(point, current.last, direction / (24 * density))
                current.last = point
            }
            is Gesture.FinishingTap -> {
                if (event.point(0).movedFrom(current.origin, slop)) gesture = Gesture.Suppressed
            }
            else -> Unit
        }
    }

    private fun liftOneFinger(event: MotionEvent) {
        val two = gesture as? Gesture.TwoFingers ?: return
        move(event)
        val remaining = 1 - event.actionIndex
        val remainingId = event.getPointerId(remaining)
        val origin = if (remainingId == two.firstId) two.firstOrigin else two.secondOrigin
        endGesture()
        gesture = if (two.tap && isTwoFingerTap(event)) {
            Gesture.FinishingTap(remainingId, origin)
        } else {
            Gesture.Suppressed
        }
    }

    private fun isTwoFingerTap(event: MotionEvent) = event.eventTime - event.downTime <= 300

    private fun accumulate(point: Point, last: Point, scale: Float) {
        val needsFrame = abs(pendingX) < 1f && abs(pendingY) < 1f
        pendingX += (point.x - last.x) * scale
        pendingY += (point.y - last.y) * scale
        if (needsFrame && (abs(pendingX) >= 1f || abs(pendingY) >= 1f)) postOnAnimation(flushFrame)
    }

    private fun flush() {
        removeCallbacks(flushFrame)
        val scrolling = gesture is Gesture.TwoFingers
        val limit = if (scrolling) 20 else 500
        val dx = pendingX.toInt().coerceIn(-limit, limit)
        val dy = pendingY.toInt().coerceIn(-limit, limit)
        pendingX %= 1f
        pendingY %= 1f
        if (dx != 0 || dy != 0) {
            send(if (scrolling) InputEvent.Scroll(dx, dy) else InputEvent.Move(dx, dy))
        }
    }

    private fun endGesture() {
        removeCallbacks(beginDrag)
        flush()
        val dragging = (gesture as? Gesture.Pointer)?.phase == Phase.DRAG
        gesture = Gesture.Idle
        pendingX = 0f
        pendingY = 0f
        if (dragging) send(InputEvent.Button(MouseButton.LEFT, false))
    }

    private fun click(button: MouseButton) {
        send(InputEvent.Button(button, true))
        send(InputEvent.Button(button, false))
    }

    private fun MotionEvent.point(index: Int) = Point(getX(index), getY(index))

    private fun midpoint(first: Point, second: Point) =
        Point((first.x + second.x) / 2, (first.y + second.y) / 2)

    override fun onDraw(canvas: Canvas) {
        val radius = 24 * density
        paint.color = Color.rgb(25, 33, 43)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
        if (!isEnabled) {
            paint.color = Color.rgb(159, 173, 185)
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT
            canvas.drawText("Waiting for a computer", width / 2f, height / 2f, paint)
            return
        }
        paint.color = if (gesture == Gesture.Idle) Color.rgb(57, 76, 90) else Color.rgb(95, 144, 127)
        val spacing = 24 * density
        val columns = (width / spacing).toInt() - 1
        val rows = (height / spacing).toInt() - 1
        val left = (width - (columns - 1) * spacing) / 2
        val top = (height - (rows - 1) * spacing) / 2
        for (column in 0 until columns) {
            for (row in 0 until rows) {
                canvas.drawCircle(left + column * spacing, top + row * spacing, density, paint)
            }
        }
    }
}
