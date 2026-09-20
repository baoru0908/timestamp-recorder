package com.timestamp.recorder

import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * 轻量 HSV 取色器（无第三方库）：顶部色相滑条 + 中间 饱和×明度 面板 + 预览 + 确定。
 * 用于事件色的「自定义色」。
 */
class ColorPickerDialog(
    context: Context,
    initial: Int,
    private val onPick: (Int) -> Unit
) : Dialog(context) {

    private var hue = 0f
    private var sat = 1f
    private var valx = 1f
    private lateinit var preview: View
    private lateinit var svView: SVView

    init {
        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)
        hue = hsv[0]; sat = hsv[1]; valx = hsv[2]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * context.resources.displayMetrics.density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // 预览块
        preview = View(context)
        val ph = (48 * context.resources.displayMetrics.density).toInt()
        preview.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ph).apply { bottomMargin = pad / 2 }
        root.addView(preview)

        // 饱和×明度面板
        svView = SVView(context).apply {
            hue = this@ColorPickerDialog.hue
            sat = this@ColorPickerDialog.sat
            valx = this@ColorPickerDialog.valx
        }
        svView.onChange = { s, v -> sat = s; valx = v; updatePreview() }
        val svSize = (260 * context.resources.displayMetrics.density).toInt()
        root.addView(svView, LinearLayout.LayoutParams(svSize, svSize).apply { bottomMargin = pad / 2 })

        // 色相条
        val hueBar = HueBar(context).apply { hue = this@ColorPickerDialog.hue }
        hueBar.onChange = { h -> hue = h; svView.hue = h; updatePreview() }
        root.addView(hueBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (28 * context.resources.displayMetrics.density).toInt()
        ).apply { bottomMargin = pad })

        val btn = Button(context).apply { text = "确定" }
        btn.setOnClickListener {
            onPick(Color.HSVToColor(floatArrayOf(hue, sat, valx)))
            dismiss()
        }
        root.addView(btn)

        setContentView(root)
        updatePreview()
    }

    private fun currentColor(): Int = Color.HSVToColor(floatArrayOf(hue, sat, valx))

    private fun updatePreview() {
        preview.setBackgroundColor(currentColor())
    }

    /** 饱和(横向)×明度(纵向) 面板 */
    class SVView(context: Context) : View(context) {
        var hue: Float = 0f
            set(value) { field = value; invalidate() }
        var sat: Float = 1f
        var valx: Float = 1f
        var onChange: (Float, Float) -> Unit = { _, _ -> }
        private var bmp: Bitmap? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rebuild(w, h)
        }

        private fun rebuild(w: Int, h: Int) {
            if (w <= 0 || h <= 0) return
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val px = IntArray(w * h)
            for (y in 0 until h) {
                val v = 1f - y.toFloat() / (h - 1)
                for (x in 0 until w) {
                    val s = x.toFloat() / (w - 1)
                    px[y * w + x] = Color.HSVToColor(floatArrayOf(hue, s, v))
                }
            }
            bmp!!.setPixels(px, 0, w, 0, 0, w, h)
        }

        override fun onDraw(canvas: Canvas) {
            bmp?.let { canvas.drawBitmap(it, 0f, 0f, paint) }
            val cx = sat * width
            val cy = (1f - valx) * height
            canvas.drawCircle(cx, cy, 12f, handle)
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE -> {
                    sat = (event.x / width).coerceIn(0f, 1f)
                    valx = 1f - (event.y / height).coerceIn(0f, 1f)
                    onChange(sat, valx)
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    /** 色相滑条 */
    class HueBar(context: Context) : View(context) {
        var hue: Float = 0f
            set(value) { field = value; invalidate() }
        var onChange: (Float) -> Unit = {}
        private var bmp: Bitmap? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w <= 0) return
            bmp = Bitmap.createBitmap(w, 1, Bitmap.Config.ARGB_8888)
            val px = IntArray(w)
            for (x in 0 until w) {
                px[x] = Color.HSVToColor(floatArrayOf(360f * x / (w - 1), 1f, 1f))
            }
            bmp!!.setPixels(px, 0, w, 0, 0, w, 1)
        }

        override fun onDraw(canvas: Canvas) {
            bmp?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint) }
            val x = hue / 360f * width
            canvas.drawLine(x, 0f, x, height.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 4f })
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE -> {
                    onChange(360f * (event.x / width).coerceIn(0f, 1f))
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
