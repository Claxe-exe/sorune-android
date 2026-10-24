package com.claxe.sorune

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Sorune's mascot, as on the computer: only her head peeks over an edge (eyes just above it).
 * She is pleased when a page has loaded and dozes off when the phone is left alone.
 * Touches pass through her.
 */
class MascotView(context: Context) : FrameLayout(context) {
    enum class Mood { Neutral, Happy, Sleepy }

    /** Source-pixel measurements of each pose: bottom of the eyes, top of the hair, centre of the head. */
    private class Pose(val drawable: Int, val eyeBottom: Float, val hairTop: Float, val headCenterX: Float)

    private val poses = mapOf(
        Mood.Neutral to Pose(R.drawable.mascot_neutral, 262f, 45f, 232f),
        Mood.Happy to Pose(R.drawable.mascot_happy, 238f, 30f, 270f),
        Mood.Sleepy to Pose(R.drawable.mascot_sleepy, 300f, 55f, 262f),
    )

    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.MATRIX }
    private val density = resources.displayMetrics.density
    var mood = Mood.Neutral
        private set

    init {
        clipChildren = true
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        show(Mood.Neutral, animate = false)
    }

    /** No touches: the page under her keeps working. */
    override fun dispatchTouchEvent(event: android.view.MotionEvent?) = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    fun show(next: Mood, animate: Boolean = true) {
        if (next == mood && image.drawable != null) return
        mood = next
        image.setImageResource(poses.getValue(next).drawable)
        fit()
        image.alpha = if (next == Mood.Sleepy) 0.75f else 1f
        if (animate && next == Mood.Happy) {
            // A small hop.
            image.animate().cancel()
            image.translationY = 0f
            image.animate().translationY(-6 * density).setDuration(140).withEndAction {
                image.animate().translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            }.start()
        }
    }

    /** Peeks in from below her edge. */
    fun peekIn() {
        image.translationY = height.toFloat().coerceAtLeast(40 * density)
        image.animate().translationY(0f).setDuration(420).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    /** The visible head (hair to eyes) fills most of the view; the eyes sit at its bottom edge. */
    private fun fit() {
        val pose = poses.getValue(mood)
        val bitmap = (image.drawable as? BitmapDrawable)?.bitmap ?: return
        if (width == 0 || height == 0) return
        // Drawables in drawable-nodpi keep their pixel size, so measurements map one to one.
        val scaleToSource = bitmap.width / image.drawable.intrinsicWidth.toFloat()
        val head = height - 10 * density
        val scale = head / ((pose.eyeBottom - pose.hairTop) / scaleToSource)
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(width / 2f - pose.headCenterX / scaleToSource * scale, height - pose.eyeBottom / scaleToSource * scale)
        image.imageMatrix = matrix
    }
}
