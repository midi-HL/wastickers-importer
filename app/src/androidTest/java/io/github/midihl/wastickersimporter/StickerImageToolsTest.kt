package io.github.midihl.wastickersimporter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class StickerImageToolsTest {
    @Test
    fun connectedRemovalPreservesAnEnclosedRegionOfTheSameColor() = withImages { images ->
        val source = images.create(Color.WHITE)
        Canvas(source).apply {
            drawRect(144f, 144f, 368f, 368f, Paint().apply { color = Color.RED })
            drawRect(192f, 192f, 320f, 320f, Paint().apply { color = Color.WHITE })
        }

        val result = images.keep(StickerImageTools.removeConnected(source, 0, 0, 10))

        assertEquals(0, Color.alpha(result.getPixel(32, 32)))
        assertEquals(0, Color.alpha(result.getPixel(480, 480)))
        assertEquals(Color.RED, result.getPixel(160, 256))
        assertEquals(Color.WHITE, result.getPixel(256, 256))
        assertEquals(Color.WHITE, source.getPixel(32, 32))
    }

    @Test
    fun circleCropFromANonSquareViewKeepsTheCenterAndTransparentCorners() = withCanvas { view, images ->
        view.setImage(images.create(Color.RED))
        view.begin(StickerCanvasView.Tool.CIRCLE)
        gesture(view, 128f to 128f, 384f to 416f)

        assertTrue(view.apply())
        val result = images.keep(view.snapshot())
        assertEquals(Color.RED, result.getPixel(256, 256))
        listOf(16 to 16, 496 to 16, 16 to 496, 496 to 496).forEach { (x, y) ->
            assertEquals(0, Color.alpha(result.getPixel(x, y)))
        }
        assertEquals(Color.RED, result.getPixel(256, 32))
        assertTrue(view.canUndo)
        view.undo()
        assertEquals(Color.RED, images.keep(view.snapshot()).getPixel(16, 16))
    }

    @Test
    fun freeCropMasksOutsideTheDrawnTriangle() = withCanvas { view, images ->
        view.setImage(images.create(Color.BLUE))
        view.begin(StickerCanvasView.Tool.FREE)
        gesture(view, 128f to 128f, 384f to 128f, 128f to 384f)

        assertTrue(view.apply())
        val result = images.keep(view.snapshot())
        assertEquals(Color.BLUE, result.getPixel(64, 64))
        assertEquals(Color.BLUE, result.getPixel(224, 224))
        assertEquals(0, Color.alpha(result.getPixel(448, 448)))
        assertEquals(0, Color.alpha(result.getPixel(384, 256)))
    }

    @Test
    fun outlineAddsPixelsOutsideTheSubjectWithoutChangingItsInterior() = withImages { images ->
        val source = images.create()
        Canvas(source).drawRect(192f, 192f, 320f, 320f, Paint().apply { color = Color.RED })

        val result = images.keep(StickerImageTools.outline(source, 8f, Color.WHITE))

        assertEquals(Color.RED, result.getPixel(192, 256))
        assertEquals(Color.RED, result.getPixel(256, 256))
        assertEquals(Color.RED, result.getPixel(319, 256))
        assertEquals(Color.WHITE, result.getPixel(187, 256))
        assertEquals(Color.WHITE, result.getPixel(324, 256))
        assertEquals(0, Color.alpha(result.getPixel(176, 256)))
        assertEquals(0, Color.alpha(source.getPixel(187, 256)))
    }

    @Test
    fun rotationMirrorsAndScalingMoveDistinctSubjectsAroundTheCanvasCenter() = withImages { images ->
        val source = images.create()
        Canvas(source).apply {
            drawRect(64f, 112f, 128f, 176f, Paint().apply { color = Color.RED })
            drawRect(336f, 304f, 400f, 368f, Paint().apply { color = Color.BLUE })
        }

        val rotated = images.keep(StickerImageTools.transform(source, 90f, false, false, 1f))
        assertEquals(Color.RED, rotated.getPixel(368, 96))
        assertEquals(Color.BLUE, rotated.getPixel(176, 368))
        assertEquals(0, Color.alpha(rotated.getPixel(96, 144)))

        val mirroredX = images.keep(StickerImageTools.transform(source, 0f, true, false, 1f))
        assertEquals(Color.RED, mirroredX.getPixel(416, 144))
        assertEquals(Color.BLUE, mirroredX.getPixel(144, 336))
        assertEquals(0, Color.alpha(mirroredX.getPixel(96, 144)))

        val mirroredY = images.keep(StickerImageTools.transform(source, 0f, false, true, 1f))
        assertEquals(Color.RED, mirroredY.getPixel(96, 368))
        assertEquals(Color.BLUE, mirroredY.getPixel(368, 176))

        val scaled = images.keep(StickerImageTools.transform(source, 0f, false, false, 0.5f))
        assertEquals(Color.RED, scaled.getPixel(176, 200))
        assertEquals(Color.BLUE, scaled.getPixel(312, 296))
        assertEquals(0, Color.alpha(scaled.getPixel(96, 144)))
        assertEquals(0, Color.alpha(scaled.getPixel(156, 200)))
    }

    @Test
    fun brightnessAndDesaturationPreservePartialAndEmptyAlpha() = withImages { images ->
        val source = images.create()
        Canvas(source).drawRect(128f, 128f, 384f, 384f,
            Paint().apply { color = Color.argb(128, 80, 120, 160) })
        val original = source.getPixel(256, 256)

        val brighter = images.keep(StickerImageTools.adjust(source, 40f, 1f))
        val brightPixel = brighter.getPixel(256, 256)
        assertEquals(Color.alpha(original), Color.alpha(brightPixel))
        assertTrue(abs(Color.red(brightPixel) - Color.red(original) - 40) <= 2)
        assertTrue(abs(Color.green(brightPixel) - Color.green(original) - 40) <= 2)
        assertTrue(abs(Color.blue(brightPixel) - Color.blue(original) - 40) <= 2)
        assertEquals(0, Color.alpha(brighter.getPixel(0, 0)))

        val gray = images.keep(StickerImageTools.adjust(source, 0f, 0f))
        val grayPixel = gray.getPixel(256, 256)
        assertEquals(Color.alpha(original), Color.alpha(grayPixel))
        assertTrue(abs(Color.red(grayPixel) - Color.green(grayPixel)) <= 2)
        assertTrue(abs(Color.green(grayPixel) - Color.blue(grayPixel)) <= 2)
        assertTrue(Color.red(grayPixel) in 90..140)
        assertEquals(0, Color.alpha(gray.getPixel(0, 0)))
        assertEquals(original, source.getPixel(256, 256))
    }

    @Test
    fun applyingAPreviewCreatesOneUndoStepAndRedoRestoresIt() = withCanvas { view, images ->
        view.setImage(images.create(Color.BLACK))
        assertFalse(view.canUndo)
        view.begin(StickerCanvasView.Tool.NONE)
        view.preview(images.create(Color.RED))
        view.preview(images.create(Color.BLUE))
        assertTrue(view.inTransaction)
        assertFalse(view.canUndo)
        assertFalse(view.canRedo)
        assertEquals(Color.BLACK, images.keep(view.baselineCopy()).getPixel(256, 256))
        assertEquals(Color.BLUE, images.keep(view.snapshot()).getPixel(256, 256))
        view.undo()
        assertEquals(Color.BLUE, images.keep(view.snapshot()).getPixel(256, 256))

        assertTrue(view.apply())
        assertFalse(view.inTransaction)
        assertTrue(view.canUndo)
        view.undo()
        assertEquals(Color.BLACK, images.keep(view.snapshot()).getPixel(256, 256))
        assertFalse(view.canUndo)
        assertTrue(view.canRedo)
        view.redo()
        assertEquals(Color.BLUE, images.keep(view.snapshot()).getPixel(256, 256))
        assertFalse(view.canRedo)
    }

    @Test
    fun cancellingAPreviewRestoresPixelsAndPreservesTheExistingRedo() = withCanvas { view, images ->
        view.setImage(images.create())
        commit(view, images.create(Color.RED))
        view.undo()
        assertTrue(view.canRedo)
        view.begin(StickerCanvasView.Tool.NONE)
        view.preview(images.create(Color.BLUE))
        val baselineCopy = images.keep(view.baselineCopy())
        baselineCopy.eraseColor(Color.GREEN)
        assertFalse(view.canRedo)

        view.cancel()

        assertFalse(view.inTransaction)
        assertFalse(view.canUndo)
        assertTrue(view.canRedo)
        assertEquals(0, Color.alpha(images.keep(view.snapshot()).getPixel(256, 256)))
        view.redo()
        assertEquals(Color.RED, images.keep(view.snapshot()).getPixel(256, 256))
    }

    @Test
    fun committingAfterUndoReplacesTheRedoBranch() = withCanvas { view, images ->
        view.setImage(images.create(Color.BLACK))
        commit(view, images.create(Color.RED))
        commit(view, images.create(Color.GREEN))
        view.undo()
        assertEquals(Color.RED, images.keep(view.snapshot()).getPixel(256, 256))
        assertTrue(view.canRedo)

        commit(view, images.create(Color.BLUE))

        assertFalse(view.canRedo)
        view.redo()
        assertEquals(Color.BLUE, images.keep(view.snapshot()).getPixel(256, 256))
        view.undo()
        assertEquals(Color.RED, images.keep(view.snapshot()).getPixel(256, 256))
        view.undo()
        assertEquals(Color.BLACK, images.keep(view.snapshot()).getPixel(256, 256))
        assertFalse(view.canUndo)
    }

    @Test
    fun multilineTextSnapshotContainsBothLinesAndMatchesTheCommittedImage() = withCanvas { view, images ->
        view.setImage(images.create())
        view.begin(StickerCanvasView.Tool.TEXT)
        view.textSizePx = 40f
        view.textColor = Color.MAGENTA
        view.textValue = "MMMM\n\nMMMM"
        val preview = images.keep(view.snapshot())
        val expected = pixels(preview)
        val bands = occupiedRowBands(preview)
        assertEquals(2, bands.size)
        assertTrue(bands[1].first - bands[0].last >= 30)
        assertTrue(bands[0].first < 256)
        assertTrue(bands[1].last > 256)
        assertEquals(0, Color.alpha(preview.getPixel(0, 0)))
        assertEquals(0, Color.alpha(preview.getPixel(20, 256)))
        assertTrue(expected.any { Color.alpha(it) > 0 && Color.red(it) > 200 && Color.blue(it) > 200 })
        preview.setPixel(0, 0, Color.GREEN)
        assertEquals(0, Color.alpha(images.keep(view.snapshot()).getPixel(0, 0)))
        assertEquals(0, Color.alpha(images.keep(view.baselineCopy()).getPixel(256, 256)))

        assertTrue(view.apply())
        assertArrayEquals(expected, pixels(images.keep(view.snapshot())))
        view.undo()
        assertTrue(pixels(images.keep(view.snapshot())).all { Color.alpha(it) == 0 })
        view.redo()
        assertArrayEquals(expected, pixels(images.keep(view.snapshot())))
    }

    @Test
    fun historyRetainsTheMostRecentTwelveEditsInBothDirections() = withCanvas { view, images ->
        fun color(step: Int) = Color.rgb(step, step * 2, step * 3)
        view.setImage(images.create(color(0)))
        for (step in 1..15) commit(view, images.create(color(step)))

        repeat(12) { index ->
            assertTrue(view.canUndo)
            view.undo()
            assertEquals(color(14 - index), images.keep(view.snapshot()).getPixel(256, 256))
        }
        assertFalse(view.canUndo)
        view.undo()
        assertEquals(color(3), images.keep(view.snapshot()).getPixel(256, 256))
        repeat(12) { index ->
            assertTrue(view.canRedo)
            view.redo()
            assertEquals(color(4 + index), images.keep(view.snapshot()).getPixel(256, 256))
        }
        assertFalse(view.canRedo)
        assertTrue(view.canUndo)
    }

    @Test
    fun missingOrTinyCropSelectionDoesNotCommitOrLoseTheTransaction() = withCanvas { view, images ->
        view.setImage(images.create(Color.RED))
        view.begin(StickerCanvasView.Tool.CIRCLE)
        assertFalse(view.apply())
        assertTrue(view.inTransaction)
        assertFalse(view.canUndo)
        gesture(view, 100f to 100f, 101f to 101f)
        assertFalse(view.apply())
        assertTrue(view.inTransaction)
        assertEquals(Color.RED, images.keep(view.snapshot()).getPixel(256, 256))

        view.cancel()

        assertFalse(view.inTransaction)
        assertFalse(view.canUndo)
        assertEquals(Color.RED, images.keep(view.snapshot()).getPixel(256, 256))
    }

    private class Images {
        private val owned = mutableListOf<Bitmap>()

        fun keep(bitmap: Bitmap): Bitmap = bitmap.also { owned.add(it) }

        fun create(color: Int = Color.TRANSPARENT): Bitmap =
            keep(Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)).apply { eraseColor(color) }

        fun recycle() = owned.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun withImages(block: (Images) -> Unit) {
        val images = Images()
        try {
            block(images)
        } finally {
            images.recycle()
        }
    }

    private fun withCanvas(block: (StickerCanvasView, Images) -> Unit) = withImages { images ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                val view = StickerCanvasView(instrumentation.targetContext)
                try {
                    view.layout(0, 0, 640, 512)
                    val screen = images.keep(Bitmap.createBitmap(640, 512, Bitmap.Config.ARGB_8888))
                    view.draw(Canvas(screen))
                    block(view, images)
                } finally {
                    view.release()
                }
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    private fun commit(view: StickerCanvasView, image: Bitmap) {
        view.begin(StickerCanvasView.Tool.NONE)
        view.preview(image)
        assertTrue(view.apply())
    }

    private fun gesture(view: StickerCanvasView, vararg points: Pair<Float, Float>) {
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, point: Pair<Float, Float>, index: Int) {
            // 640×512 视图的图片左边缘位于 x=64，触摸点使用图片坐标。
            val event = MotionEvent.obtain(downTime, downTime + index * 16L, action,
                point.first + 64f, point.second, 0)
            try {
                assertTrue(view.onTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, points.first(), 0)
        points.drop(1).forEachIndexed { index, point -> send(MotionEvent.ACTION_MOVE, point, index + 1) }
        send(MotionEvent.ACTION_UP, points.last(), points.size)
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun occupiedRowBands(bitmap: Bitmap): List<IntRange> {
        val pixels = pixels(bitmap)
        val bands = mutableListOf<IntRange>()
        var start = -1
        for (y in 0 until bitmap.height) {
            val occupied = (0 until bitmap.width).any { x -> Color.alpha(pixels[y * bitmap.width + x]) > 0 }
            if (occupied && start < 0) start = y
            if (!occupied && start >= 0) {
                bands.add(start until y)
                start = -1
            }
        }
        if (start >= 0) bands.add(start until bitmap.height)
        return bands
    }
}
