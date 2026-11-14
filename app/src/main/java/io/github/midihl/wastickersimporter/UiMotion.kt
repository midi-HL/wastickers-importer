package io.github.midihl.wastickersimporter

import android.content.Context
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar

object UiMotion {
    private const val DURATION = 320L
    private const val STAGGER = 28L

    fun animationsEnabled(context: Context): Boolean {
        val resolver = context.contentResolver
        val scale = android.provider.Settings.Global.getFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        return scale > 0f
    }

    /** MD3-emphasized fade + slight vertical displacement, with per-item stagger. */
    fun staggerIn(views: Sequence<View>, context: Context) {
        if (!animationsEnabled(context)) {
            views.forEach { it.alpha = 1f; it.translationY = 0f }
            return
        }
        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 24f
            view.animate()
                .alpha(1f).translationY(0f)
                .setDuration(DURATION)
                .setStartDelay(minOf(index, 8) * STAGGER)
                .setInterpolator(DecelerateInterpolator(1.4f))
                .start()
        }
    }

    fun transition(context: Context) {
        if (!animationsEnabled(context)) return
        // Framework activity transitions are configured per Activity via window attributes.
    }

    fun snackbar(view: View, message: String): Snackbar = Snackbar.make(view, message, Snackbar.LENGTH_LONG)

    fun RecyclerView.childrenSequence(): Sequence<View> = sequence {
        for (i in 0 until childCount) yield(getChildAt(i))
    }
}
