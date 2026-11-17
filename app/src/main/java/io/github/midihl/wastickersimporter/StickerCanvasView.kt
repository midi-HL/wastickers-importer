package io.github.midihl.wastickersimporter

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.min

class StickerCanvasView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class Tool { NONE, RECT, CIRCLE, FREE, ERASE, RESTORE, REMOVE, TEXT, DRAW }
    var tool = Tool.NONE
        private set
    var brushSize = 26f
    var brushColor = Color.WHITE
    var tolerance = 40
    var textValue = ""
        set(value) { field = value; invalidate() }
    var textSizePx = 48f
        set(value) { field = value; invalidate() }
    var textColor = Color.WHITE
        set(value) { field = value; invalidate() }
    var onHistoryChanged: (() -> Unit)? = null
    var onInteraction: (() -> Unit)? = null
    private var image = StickerImageTools.empty()
    private var restoreImage = StickerImageTools.empty()
    private var baseline: Bitmap? = null
    private val undo = ArrayDeque<Bitmap>()
    private val redo = ArrayDeque<Bitmap>()
    private val paint = Paint(3)
    private val imageRect = RectF()
    private val selection = Path()
    private var selectionBounds = RectF()
    private var selected = false
    private var startX = 0f
    private var startY = 0f
    private var previousX = 0f
    private var previousY = 0f
    private var textX = 256f
    private var textY = 256f
    val canUndo get() = undo.isNotEmpty() && baseline == null
    val canRedo get() = redo.isNotEmpty() && baseline == null
    val inTransaction get() = baseline != null

    init { isFocusable = true; setLayerType(LAYER_TYPE_SOFTWARE, null) }

    fun setImage(bitmap: Bitmap, original: Bitmap? = null) {
        image.recycle(); restoreImage.recycle(); baseline?.recycle(); baseline = null
        clearHistory()
        image = StickerImageTools.copy(bitmap)
        restoreImage = StickerImageTools.copy(original ?: bitmap)
        tool = Tool.NONE; selected = false
        invalidate(); onHistoryChanged?.invoke()
    }

    private fun clearHistory() {
        undo.forEach { it.recycle() }; redo.forEach { it.recycle() }
        undo.clear(); redo.clear()
    }

    fun begin(tool: Tool) {
        check(baseline == null)
        baseline = StickerImageTools.copy(image)
        this.tool = tool; selected = false; selection.reset()
        textX = 256f; textY = 256f; textValue = ""
        invalidate(); onHistoryChanged?.invoke()
    }

    fun preview(result: Bitmap) {
        check(baseline != null)
        image.recycle(); image = result; invalidate()
    }

    fun baselineCopy(): Bitmap = StickerImageTools.copy(baseline ?: image)
    fun snapshot(): Bitmap = StickerImageTools.copy(image).also {
        if (tool == Tool.TEXT) drawText(Canvas(it))
    }

    fun apply(): Boolean {
        val before = baseline ?: return false
        if (tool in listOf(Tool.RECT, Tool.CIRCLE, Tool.FREE)) {
            if (!selected || selectionBounds.width() < 3 || selectionBounds.height() < 3) return false
            val cropped = StickerImageTools.crop(image, selection, selectionBounds)
            image.recycle(); image = cropped
        }
        if (tool == Tool.TEXT) drawText(Canvas(image))
        redo.forEach { it.recycle() }; redo.clear()
        undo.addLast(before)
        while (undo.size + redo.size > 12) undo.removeFirst().recycle()
        baseline = null; tool = Tool.NONE; selected = false; textValue = ""
        invalidate(); onHistoryChanged?.invoke()
        return true
    }

    fun cancel() {
        val before = baseline ?: return
        image.recycle(); image = before; baseline = null
        tool = Tool.NONE; selected = false; textValue = ""
        invalidate(); onHistoryChanged?.invoke()
    }

    fun undo() {
        if (!canUndo) return
        redo.addLast(image); image = undo.removeLast()
        invalidate(); onHistoryChanged?.invoke()
    }

    fun redo() {
        if (!canRedo) return
        undo.addLast(image); image = redo.removeLast()
        invalidate(); onHistoryChanged?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        if (side <= 0) return
        imageRect.set((width-side)/2, (height-side)/2, (width+side)/2, (height+side)/2)
        val save = canvas.save()
        canvas.translate(imageRect.left,imageRect.top); canvas.scale(side/512f,side/512f)
        canvas.clipRect(0f,0f,512f,512f)
        for (y in 0 until 32) for (x in 0 until 32) {
            paint.color = if ((x+y)%2 == 0) 0xffe5e5e5.toInt() else 0xffc5c5c5.toInt()
            canvas.drawRect(x*16f,y*16f,(x+1)*16f,(y+1)*16f,paint)
        }
        paint.color = Color.WHITE; paint.style = Paint.Style.FILL
        canvas.drawBitmap(image,0f,0f,paint)
        if (tool == Tool.TEXT) {
            drawText(canvas)
            val layout = textLayout()
            paint.color = Color.BLACK; paint.strokeWidth = 3f; paint.style = Paint.Style.STROKE
            canvas.drawRect(textX-layout.width/2f-4,textY-layout.height/2f-4,textX+layout.width/2f+4,textY+layout.height/2f+4,paint)
            paint.color = Color.WHITE; paint.strokeWidth = 1.5f
            canvas.drawRect(textX-layout.width/2f-4,textY-layout.height/2f-4,textX+layout.width/2f+4,textY+layout.height/2f+4,paint)
            paint.style = Paint.Style.FILL
        }
        if (selected) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = Color.BLACK
            canvas.drawPath(selection,paint)
            paint.strokeWidth = 2f; paint.color = Color.WHITE; paint.pathEffect = DashPathEffect(floatArrayOf(8f,6f),0f)
            canvas.drawPath(selection,paint)
            paint.pathEffect = null; paint.style = Paint.Style.FILL
        }
        canvas.restoreToCount(save)
    }

    private fun textLayout(): StaticLayout {
        val textPaint = TextPaint(3).apply { color = textColor; textSize = textSizePx }
        return StaticLayout.Builder.obtain(textValue,0,textValue.length,textPaint,480)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(true).build()
    }

    private fun drawText(canvas: Canvas) {
        if (textValue.isEmpty()) return
        val layout = textLayout(); val save = canvas.save()
        canvas.translate(textX-layout.width/2f,textY-layout.height/2f)
        layout.draw(canvas); canvas.restoreToCount(save)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (tool == Tool.NONE || imageRect.width() <= 0 || !isEnabled) return false
        val x = ((event.x-imageRect.left)*512/imageRect.width()).coerceIn(0f,511f)
        val y = ((event.y-imageRect.top)*512/imageRect.height()).coerceIn(0f,511f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                startX=x; startY=y; previousX=x; previousY=y
                if (tool == Tool.FREE) { selection.reset(); selection.moveTo(x,y); selected=true }
                if (tool == Tool.RECT || tool == Tool.CIRCLE) { selection.reset(); selected=true }
                if (tool == Tool.REMOVE) {
                    val result = StickerImageTools.removeConnected(image,x.toInt(),y.toInt(),tolerance)
                    image.recycle(); image=result
                }
                if (tool in listOf(Tool.ERASE, Tool.RESTORE, Tool.DRAW)) stroke(x,y,x,y)
            }
            MotionEvent.ACTION_MOVE -> {
                when (tool) {
                    Tool.RECT, Tool.CIRCLE -> {
                        val endX: Float; val endY: Float
                        if (tool == Tool.CIRCLE) {
                            val side=min(abs(x-startX),abs(y-startY))
                            endX=startX+if (x>=startX) side else -side
                            endY=startY+if (y>=startY) side else -side
                        } else { endX=x; endY=y }
                        selectionBounds.set(min(startX,endX),min(startY,endY),maxOf(startX,endX),maxOf(startY,endY))
                        selection.reset()
                        if (tool == Tool.RECT) selection.addRect(selectionBounds,Path.Direction.CW)
                        else selection.addOval(selectionBounds,Path.Direction.CW)
                    }
                    Tool.FREE -> { selection.lineTo(x,y); selection.computeBounds(selectionBounds,true) }
                    Tool.ERASE, Tool.RESTORE, Tool.DRAW -> stroke(previousX,previousY,x,y)
                    Tool.TEXT -> { textX=(textX+x-previousX).coerceIn(0f,512f); textY=(textY+y-previousY).coerceIn(0f,512f) }
                    else -> Unit
                }
                previousX=x; previousY=y
            }
            MotionEvent.ACTION_UP -> {
                if (tool == Tool.FREE) { selection.close(); selection.computeBounds(selectionBounds,true) }
                parent?.requestDisallowInterceptTouchEvent(false); performClick(); onInteraction?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        invalidate(); return true
    }

    private fun stroke(x1: Float, y1: Float, x2: Float, y2: Float) {
        val canvas = Canvas(image)
        val brush = Paint(3).apply { strokeWidth=brushSize; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND; style=Paint.Style.STROKE; color=brushColor }
        val path = Path().apply { moveTo(x1,y1); if (abs(x2-x1)+abs(y2-y1)<0.01f) lineTo(x2+0.1f,y2) else lineTo(x2,y2) }
        when (tool) {
            Tool.ERASE -> { brush.xfermode=PorterDuffXfermode(PorterDuff.Mode.CLEAR); canvas.drawPath(path,brush) }
            Tool.RESTORE -> {
                val area = Path(); brush.getFillPath(path,area)
                val save=canvas.save(); canvas.clipPath(area)
                canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
                canvas.drawBitmap(restoreImage,0f,0f,Paint(3)); canvas.restoreToCount(save)
            }
            else -> canvas.drawPath(path,brush)
        }
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    fun release() {
        image.recycle(); restoreImage.recycle(); baseline?.recycle(); baseline=null; clearHistory()
    }
}
