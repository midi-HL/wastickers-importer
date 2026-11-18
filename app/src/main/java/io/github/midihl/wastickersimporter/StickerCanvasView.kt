package io.github.midihl.wastickersimporter

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Bitmap coordinates, the output transform and the inspection viewport are deliberately separate. */
class StickerCanvasView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class Tool { NONE, TRANSFORM, RECT, ROUNDED, CIRCLE, FREE, ERASE, RESTORE, REMOVE, TEXT, DRAW }
    var tool = Tool.NONE
        private set
    var brushSize = 26f
    var brushColor = Color.rgb(103, 80, 164)
    var tolerance = 40
    var cropRadius = 72f
        set(value) { field = value.coerceIn(0f, 256f); if (tool == Tool.ROUNDED) updateCropPath(); invalidate() }
    var textValue = ""
        set(value) { field = value; invalidate() }
    var textSizePx = 48f
        set(value) { field = value.coerceIn(12f, 200f); invalidate() }
    var textColor = Color.WHITE
        set(value) { field = value; invalidate() }
    var onHistoryChanged: (() -> Unit)? = null
    var onInteraction: (() -> Unit)? = null
    var isDirectManipulation = false
        private set
    private var image = StickerImageTools.empty()
    private var restoreImage = StickerImageTools.empty()
    private var baseline: Bitmap? = null
    private val undo = ArrayDeque<Bitmap>()
    private val redo = ArrayDeque<Bitmap>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val imageRect = RectF()
    private val selection = Path()
    private val selectionBounds = RectF()
    private val selectionAtDown = Path()
    private val boundsAtDown = RectF()
    private var selectedAtDown = false
    private val contentMatrix = Matrix()
    private var selected = false
    private var startX = 0f
    private var startY = 0f
    private var previousX = 0f
    private var previousY = 0f
    private var downViewX = 0f
    private var downViewY = 0f
    private var textX = 256f
    private var textY = 256f
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var activePointer = MotionEvent.INVALID_POINTER_ID
    private var firstPointer = MotionEvent.INVALID_POINTER_ID
    private var secondPointer = MotionEvent.INVALID_POINTER_ID
    private var previousFocusX = 0f
    private var previousFocusY = 0f
    private var previousSpan = 0f
    private var suppressDrawing = false
    private var moved = false
    private var strokeBase: Bitmap? = null
    private val strokePath = Path()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.WHITE)
    private val onSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
    private val checkerLight = ColorUtils.blendARGB(surface, onSurface, .05f)
    private val checkerDark = ColorUtils.blendARGB(surface, onSurface, .13f)
    val canUndo get() = undo.isNotEmpty() && baseline == null
    val canRedo get() = redo.isNotEmpty() && baseline == null
    val inTransaction get() = baseline != null

    init { isFocusable = true }

    fun setImage(bitmap: Bitmap, original: Bitmap? = null) {
        endStroke(rollback = false)
        image.recycle(); restoreImage.recycle(); baseline?.recycle(); baseline = null
        clearHistory()
        image = StickerImageTools.copy(bitmap)
        restoreImage = StickerImageTools.copy(original ?: bitmap)
        tool = Tool.NONE; selected = false; isDirectManipulation = false
        resetViewport()
        invalidate(); onHistoryChanged?.invoke()
    }

    private fun clearHistory() {
        undo.forEach { it.recycle() }; redo.forEach { it.recycle() }
        undo.clear(); redo.clear()
    }

    fun begin(tool: Tool) {
        check(baseline == null)
        baseline = StickerImageTools.copy(image)
        this.tool = tool; selected = false; selection.reset(); selectionBounds.setEmpty()
        contentMatrix.reset(); resetViewport()
        textX = 256f; textY = 256f; textValue = ""
        if (tool == Tool.ROUNDED) {
            selectionBounds.set(0f, 0f, 512f, 512f); selected = true; updateCropPath()
        }
        invalidate(); onHistoryChanged?.invoke()
    }

    fun preview(result: Bitmap) {
        check(baseline != null)
        image.recycle(); image = result; invalidate()
    }

    fun baselineCopy(): Bitmap = StickerImageTools.copy(baseline ?: image)
    fun snapshot(): Bitmap {
        val result = if (tool == Tool.TRANSFORM) transformedImage() else StickerImageTools.copy(image)
        if (tool == Tool.TEXT) drawText(Canvas(result))
        return result
    }

    private fun transformedImage(): Bitmap = StickerImageTools.empty().also {
        Canvas(it).drawBitmap(image, contentMatrix, paint)
    }

    fun resetTransform() { contentMatrix.reset(); invalidate() }
    fun rotateTransform(degrees: Float) { contentMatrix.postRotate(degrees, 256f, 256f); invalidate() }
    fun mirrorTransform(horizontal: Boolean) {
        contentMatrix.postScale(if (horizontal) -1f else 1f, if (horizontal) 1f else -1f, 256f, 256f)
        invalidate()
    }
    fun resetViewport() { zoom = 1f; panX = 0f; panY = 0f; invalidate() }

    fun apply(): Boolean {
        val before = baseline ?: return false
        endStroke(rollback = false)
        if (tool in listOf(Tool.RECT, Tool.ROUNDED, Tool.CIRCLE, Tool.FREE)) {
            if (!selected || selectionBounds.width() < 3 || selectionBounds.height() < 3) return false
            val cropped = StickerImageTools.crop(image, selection, selectionBounds)
            image.recycle(); image = cropped
        }
        if (tool == Tool.TRANSFORM) {
            val transformed = transformedImage(); image.recycle(); image = transformed
        }
        if (tool == Tool.TEXT) {
            if (textValue.isBlank()) return false
            drawText(Canvas(image))
        }
        redo.forEach { it.recycle() }; redo.clear()
        undo.addLast(before)
        while (undo.size + redo.size > 12) undo.removeFirst().recycle()
        baseline = null; tool = Tool.NONE; selected = false; textValue = ""; isDirectManipulation = false
        resetViewport(); invalidate(); onHistoryChanged?.invoke()
        return true
    }

    fun cancel() {
        val before = baseline ?: return
        endStroke(rollback = false)
        image.recycle(); image = before; baseline = null
        tool = Tool.NONE; selected = false; textValue = ""; isDirectManipulation = false
        resetViewport(); invalidate(); onHistoryChanged?.invoke()
    }

    fun undo() {
        if (!canUndo) return
        redo.addLast(image); image = undo.removeLast()
        resetViewport(); invalidate(); onHistoryChanged?.invoke()
    }
    fun redo() {
        if (!canRedo) return
        undo.addLast(image); image = redo.removeLast()
        resetViewport(); invalidate(); onHistoryChanged?.invoke()
    }

    private fun updateImageRect() {
        val side = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        imageRect.set(paddingLeft + (width - paddingLeft - paddingRight - side) / 2f,
            paddingTop + (height - paddingTop - paddingBottom - side) / 2f,
            paddingLeft + (width - paddingLeft - paddingRight + side) / 2f,
            paddingTop + (height - paddingTop - paddingBottom + side) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateImageRect()
        if (imageRect.width() <= 0) return
        val save = canvas.save()
        canvas.translate(imageRect.left, imageRect.top)
        canvas.scale(imageRect.width() / 512f, imageRect.height() / 512f)
        canvas.clipRect(0f, 0f, 512f, 512f)
        paint.style = Paint.Style.FILL
        for (y in 0 until 16) for (x in 0 until 16) {
            paint.color = if ((x + y) % 2 == 0) checkerLight else checkerDark
            canvas.drawRect(x * 32f, y * 32f, (x + 1) * 32f, (y + 1) * 32f, paint)
        }
        canvas.translate(panX, panY); canvas.scale(zoom, zoom)
        paint.color = Color.WHITE
        if (tool == Tool.TRANSFORM) canvas.drawBitmap(image, contentMatrix, paint)
        else canvas.drawBitmap(image, 0f, 0f, paint)
        if (tool == Tool.TEXT) {
            drawText(canvas)
            val layout = textLayout()
            val halfWidth = (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) / 2f } ?: 0f
            val bounds = RectF(textX - halfWidth - 8, textY - layout.height / 2f - 4,
                textX + halfWidth + 8, textY + layout.height / 2f + 4)
            paint.color = Color.BLACK; paint.strokeWidth = 3f / zoom; paint.style = Paint.Style.STROKE
            canvas.drawRoundRect(bounds, 6f, 6f, paint)
            paint.color = Color.WHITE; paint.strokeWidth = 1.5f / zoom
            canvas.drawRoundRect(bounds, 6f, 6f, paint); paint.style = Paint.Style.FILL
        }
        if (selected) {
            if (tool != Tool.FREE || activePointer == MotionEvent.INVALID_POINTER_ID) {
                val shade = Path().apply {
                    addRect(0f, 0f, 512f, 512f, Path.Direction.CW)
                    addPath(selection); fillType = Path.FillType.EVEN_ODD
                }
                paint.color = 0x66000000; canvas.drawPath(shade, paint)
            }
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f / zoom; paint.color = Color.BLACK
            canvas.drawPath(selection, paint)
            paint.strokeWidth = 2f / zoom; paint.color = Color.WHITE
            paint.pathEffect = DashPathEffect(floatArrayOf(8f / zoom, 6f / zoom), 0f)
            canvas.drawPath(selection, paint)
            paint.pathEffect = null; paint.style = Paint.Style.FILL
        }
        canvas.restoreToCount(save)
    }

    private fun textLayout(): StaticLayout {
        val textPaint = TextPaint(3).apply { color = textColor; textSize = textSizePx }
        return StaticLayout.Builder.obtain(textValue, 0, textValue.length, textPaint, 480)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(true).build()
    }
    private fun drawText(canvas: Canvas) {
        if (textValue.isEmpty()) return
        val layout = textLayout(); val save = canvas.save()
        canvas.translate(textX - layout.width / 2f, textY - layout.height / 2f)
        layout.draw(canvas); canvas.restoreToCount(save)
    }

    private fun outputX(x: Float) = (x - imageRect.left) * 512f / imageRect.width()
    private fun outputY(y: Float) = (y - imageRect.top) * 512f / imageRect.height()
    private fun imageX(x: Float) = ((outputX(x) - panX) / zoom).coerceIn(0f, 511.99f)
    private fun imageY(y: Float) = ((outputY(y) - panY) / zoom).coerceIn(0f, 511.99f)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        updateImageRect()
        if (imageRect.width() <= 0 || !isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!imageRect.contains(event.x, event.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                activePointer = event.getPointerId(0)
                downViewX = event.x; downViewY = event.y
                startX = imageX(event.x); startY = imageY(event.y)
                previousX = startX; previousY = startY
                moved = false; suppressDrawing = false; previousSpan = 0f
                selectionAtDown.set(selection); boundsAtDown.set(selectionBounds); selectedAtDown = selected
                when (tool) {
                    Tool.FREE -> { selection.reset(); selection.moveTo(startX, startY); selected = true }
                    Tool.RECT, Tool.ROUNDED, Tool.CIRCLE -> { selectionBounds.set(startX, startY, startX, startY); selected = true; updateCropPath() }
                    Tool.ERASE, Tool.RESTORE, Tool.DRAW -> {
                        strokeBase = StickerImageTools.copy(image)
                        strokePath.reset(); strokePath.moveTo(startX, startY); renderStroke()
                    }
                    else -> Unit
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                endStroke(rollback = true)
                if (tool in listOf(Tool.RECT, Tool.ROUNDED, Tool.CIRCLE, Tool.FREE)) {
                    selected = selectedAtDown; selection.set(selectionAtDown); selectionBounds.set(boundsAtDown)
                }
                suppressDrawing = true
                startPinch(event, -1)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    pinch(event)
                } else {
                    val index = event.findPointerIndex(activePointer)
                    if (index < 0) return true
                    val vx = event.getX(index); val vy = event.getY(index)
                    val x = imageX(vx); val y = imageY(vy)
                    if (abs(vx - downViewX) + abs(vy - downViewY) > touchSlop) moved = true
                    if (tool == Tool.NONE && baseline == null && moved && !suppressDrawing) startDirectTransform()
                    when {
                        tool == Tool.TRANSFORM -> {
                            contentMatrix.postTranslate(outputX(vx) - previousX, outputY(vy) - previousY)
                        }
                        !suppressDrawing -> when (tool) {
                            Tool.RECT, Tool.ROUNDED, Tool.CIRCLE -> {
                                val side = min(abs(x - startX), abs(y - startY))
                                val ex = if (tool == Tool.CIRCLE) startX + if (x >= startX) side else -side else x
                                val ey = if (tool == Tool.CIRCLE) startY + if (y >= startY) side else -side else y
                                selectionBounds.set(min(startX, ex), min(startY, ey), maxOf(startX, ex), maxOf(startY, ey))
                                updateCropPath()
                            }
                            Tool.FREE -> { selection.lineTo(x, y); selection.computeBounds(selectionBounds, true) }
                            Tool.ERASE, Tool.RESTORE, Tool.DRAW -> {
                                strokePath.quadTo(previousX, previousY, (previousX + x) / 2f, (previousY + y) / 2f)
                                renderStroke()
                            }
                            Tool.TEXT -> { textX = (textX + x - previousX).coerceIn(0f, 512f); textY = (textY + y - previousY).coerceIn(0f, 512f) }
                            else -> Unit
                        }
                    }
                    previousX = if (tool == Tool.TRANSFORM) outputX(vx) else x
                    previousY = if (tool == Tool.TRANSFORM) outputY(vy) else y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = (0 until event.pointerCount).filter { it != event.actionIndex }
                activePointer = event.getPointerId(remaining.first())
                val index = remaining.first()
                previousX = if (tool == Tool.TRANSFORM) outputX(event.getX(index)) else imageX(event.getX(index))
                previousY = if (tool == Tool.TRANSFORM) outputY(event.getY(index)) else imageY(event.getY(index))
                if (remaining.size >= 2) startPinch(event, event.actionIndex) else previousSpan = 0f
            }
            MotionEvent.ACTION_UP -> {
                if (!suppressDrawing) {
                    when (tool) {
                        Tool.FREE -> { selection.close(); selection.computeBounds(selectionBounds, true) }
                        Tool.ERASE, Tool.RESTORE, Tool.DRAW -> {
                            strokePath.lineTo(imageX(event.x), imageY(event.y)); renderStroke()
                        }
                        Tool.REMOVE -> if (!moved) {
                            val result = StickerImageTools.removeConnected(image, startX.toInt(), startY.toInt(), tolerance)
                            image.recycle(); image = result
                        }
                        else -> Unit
                    }
                }
                endStroke(rollback = false)
                if (isDirectManipulation) apply()
                activePointer = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick(); onInteraction?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> {
                endStroke(rollback = true)
                if (isDirectManipulation) cancel()
                activePointer = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        invalidate(); return true
    }

    private fun startDirectTransform() {
        isDirectManipulation = true
        begin(Tool.TRANSFORM)
        // The initial viewport is at 1:1 when no tool is open.
        previousX = outputX(downViewX); previousY = outputY(downViewY)
    }

    private fun startPinch(event: MotionEvent, excluded: Int) {
        val indices = (0 until event.pointerCount).filter { it != excluded }
        if (indices.size < 2) return
        firstPointer = event.getPointerId(indices[0]); secondPointer = event.getPointerId(indices[1])
        val x1 = outputX(event.getX(indices[0])); val y1 = outputY(event.getY(indices[0]))
        val x2 = outputX(event.getX(indices[1])); val y2 = outputY(event.getY(indices[1]))
        previousFocusX = (x1 + x2) / 2f; previousFocusY = (y1 + y2) / 2f
        previousSpan = hypot(x2 - x1, y2 - y1)
    }

    private fun pinch(event: MotionEvent) {
        val a = event.findPointerIndex(firstPointer); val b = event.findPointerIndex(secondPointer)
        if (a < 0 || b < 0 || previousSpan < 1f) { startPinch(event, -1); return }
        val x1 = outputX(event.getX(a)); val y1 = outputY(event.getY(a))
        val x2 = outputX(event.getX(b)); val y2 = outputY(event.getY(b))
        val focusX = (x1 + x2) / 2f; val focusY = (y1 + y2) / 2f
        val span = hypot(x2 - x1, y2 - y1).coerceAtLeast(1f)
        val ratio = span / previousSpan
        if (tool == Tool.NONE && baseline == null) startDirectTransform()
        when (tool) {
            Tool.TRANSFORM -> {
                val values = FloatArray(9); contentMatrix.getValues(values)
                val currentScale = hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y])
                val factor = (currentScale * ratio).coerceIn(.15f, 6f) / currentScale
                contentMatrix.postScale(factor, factor, previousFocusX, previousFocusY)
                contentMatrix.postTranslate(focusX - previousFocusX, focusY - previousFocusY)
            }
            Tool.TEXT -> {
                textSizePx *= ratio
                textX = (textX + (focusX - previousFocusX) / zoom).coerceIn(0f, 512f)
                textY = (textY + (focusY - previousFocusY) / zoom).coerceIn(0f, 512f)
            }
            else -> {
                val next = (zoom * ratio).coerceIn(1f, 5f)
                val factor = next / zoom
                panX = focusX - (previousFocusX - panX) * factor
                panY = focusY - (previousFocusY - panY) * factor
                zoom = next
                panX = panX.coerceIn(512f * (1f - zoom), 0f)
                panY = panY.coerceIn(512f * (1f - zoom), 0f)
            }
        }
        previousFocusX = focusX; previousFocusY = focusY; previousSpan = span
    }

    private fun updateCropPath() {
        selection.reset()
        when (tool) {
            Tool.CIRCLE -> selection.addOval(selectionBounds, Path.Direction.CW)
            Tool.ROUNDED -> {
                val radius = min(cropRadius, min(selectionBounds.width(), selectionBounds.height()) / 2f)
                selection.addRoundRect(selectionBounds, radius, radius, Path.Direction.CW)
            }
            else -> selection.addRect(selectionBounds, Path.Direction.CW)
        }
    }

    private fun renderStroke() {
        val source = strokeBase ?: return
        val canvas = Canvas(image)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(source, 0f, 0f, paint)
        val brush = Paint(3).apply {
            strokeWidth = brushSize; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE; color = brushColor
        }
        val path = Path(strokePath).apply {
            if (isEmpty) { moveTo(startX, startY); lineTo(startX + .01f, startY) }
        }
        // A zero-length path has no visible cap on some renderers.
        path.addCircle(startX, startY, .01f, Path.Direction.CW)
        when (tool) {
            Tool.ERASE -> { brush.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR); canvas.drawPath(path, brush) }
            Tool.RESTORE -> {
                val area = Path(); brush.getFillPath(path, area)
                val save = canvas.save(); canvas.clipPath(area)
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                canvas.drawBitmap(restoreImage, 0f, 0f, paint); canvas.restoreToCount(save)
            }
            else -> canvas.drawPath(path, brush)
        }
    }

    private fun endStroke(rollback: Boolean) {
        val before = strokeBase ?: return
        if (rollback) { image.recycle(); image = before } else before.recycle()
        strokeBase = null; strokePath.reset()
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    fun release() {
        endStroke(rollback = false)
        image.recycle(); restoreImage.recycle(); baseline?.recycle(); baseline = null; clearHistory()
    }
}
