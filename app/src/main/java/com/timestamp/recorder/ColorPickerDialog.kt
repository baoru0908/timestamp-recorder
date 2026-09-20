package com.timestamp.recorder

import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton

/**
 * 轻量 HSV 取色器（无第三方库）：顶部色相滑条 + 中间 饱和×明度 面板 + 预览 + 确定。
 * 用于事件色的「自定义色」。
 */
class ColorPickerDialog(
    context: Context,
    initial: Int,
    private val onPick: (Int) -> Unit
) : Dialog(context, R.style.Theme_Timestamp_Dialog) {

    private var hue = 0f
    private var sat = 1f
    private var valx = 1f
    private lateinit var preview: View
    private lateinit var svView: SVView

    init {
        // 初始色先夹一次：像 #FFFFFF 这样的历史颜色也能被纠正回"白字可读"的状态
        val hsv = FloatArray(3)
        Color.colorToHSV(EventColors.forWhiteText(initial), hsv)
        hue = hsv[0]; sat = hsv[1]; valx = hsv[2]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = context.resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 玻璃浮层的底自己画（主题那边的 windowBackground 是透明的），
            // 颜色取 App 资源 → 夜间是深玻璃、日间是浅玻璃，不会与文字色打架
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_dialog)
            setPadding(pad, (16 * d).toInt(), pad, pad)
        }

        // 标题画在内容里（不用 Dialog.setTitle）：
        // 页面主题是 NoActionBar（windowNoTitle=true），Window 标题不一定显示，
        // 自己加一个 TextView 才能保证"这个弹窗在改什么颜色"永远可见。
        val title = android.widget.TextView(context).apply {
            text = context.getString(R.string.custom_color_title)
            textSize = 18f
            // 直接取 App 的颜色资源：夜/日各一套，绝不会出现"浮层浅底 + 浅色字"
            setTextColor(context.getColor(R.color.md_on_surface))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * d).toInt() }
        }
        root.addView(title)

        // 预览块
        preview = View(context)
        val ph = (48 * d).toInt()
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
        // 自绘面板对读屏软件是空白的，必须自己说清楚它能干什么（评审 A-1）
        svView.contentDescription = context.getString(R.string.cd_color_sv_panel)
        // 尺寸自适应：固定 260dp 在 320dp 窄屏或横屏下会溢出被裁（评审 C-5）。
        // 取「屏宽 − 2×内边距 − 一点呼吸」与 260dp 的较小值。
        val screenW = context.resources.displayMetrics.widthPixels
        val svSize = minOf((260 * d).toInt(), (screenW - pad * 2 - (16 * d).toInt()).coerceAtLeast((160 * d).toInt()))
        root.addView(svView, LinearLayout.LayoutParams(svSize, svSize).apply { bottomMargin = pad / 2 })

        // 色相条
        val hueBar = HueBar(context).apply { hue = this@ColorPickerDialog.hue }
        hueBar.contentDescription = context.getString(R.string.cd_color_hue_bar)
        hueBar.onChange = { h ->
            hue = h
            val vTop = EventColors.maxValueForWhite(h, sat).coerceAtLeast(0.05f)
            if (valx > vTop) valx = vTop
            svView.hue = h
            svView.valx = valx
            updatePreview()
        }
        root.addView(hueBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (28 * context.resources.displayMetrics.density).toInt()
        ).apply { bottomMargin = pad })

        val btn = MaterialButton(context).apply { text = context.getString(R.string.confirm) }
        btn.setOnClickListener {
            // 再兜一次：确保落库的颜色一定能让白字达标
            onPick(EventColors.forWhiteText(Color.HSVToColor(floatArrayOf(hue, sat, valx))))
            dismiss()
        }
        root.addView(btn)

        setContentView(root)
        // 浮层加一层 dim：与确认弹窗的模态感一致（主题里关掉了 backgroundDimEnabled）
        window?.let { w ->
            w.setDimAmount(0.32f)
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setLayout(
                (context.resources.displayMetrics.widthPixels * 0.88f).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        updatePreview()
    }

    private fun currentColor(): Int = Color.HSVToColor(floatArrayOf(hue, sat, valx))

    private fun updatePreview() {
        preview.setBackgroundColor(currentColor())
    }

    /** 饱和(横向)×明度(纵向) 面板 */
    class SVView(context: Context) : View(context) {
        var hue: Float = 0f
            set(value) {
                field = value
                // ⚠️ 必须**重建位图**而不只是 invalidate：方形面板的像素颜色是烘进 Bitmap 的，
                //    只重绘等于把旧色相再画一遍 —— 表现就是"拖色相条，方板颜色不动"
                //    （主人 2026-09-21 反馈"颜色条与方形颜色板更新不同步"）。
                rebuild(width, height)
                invalidate()
            }
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

        /**
         * 面板不是"任意颜色都能挑"，而是**只呈现白字读得清的颜色**：
         * 每一列（饱和度固定）算一个明度上限 colMax，纵向就在 0~上限之间铺开。
         *
         * 为什么这么做：App 统一用白色文字压在事件色上，"挑到白色/极浅色"会直接让
         * 事件卡上的色点、药丸、文字全部糊成一片（2026-09-21 真机踩到 #FFFFFF 事件）。
         * 与其事后补救，不如让面板本身不给出这类颜色。
         */
        private fun rebuild(w: Int, h: Int) {
            if (w <= 0 || h <= 0) return
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val px = IntArray(w * h)
            // 每列只算一次上限（s 只跟 x 有关），260 列 × 12 次二分，开销可忽略
            val colMax = FloatArray(w) { x ->
                EventColors.maxValueForWhite(hue, x.toFloat() / (w - 1))
            }
            for (x in 0 until w) {
                val s = x.toFloat() / (w - 1)
                val vTop = colMax[x]
                for (y in 0 until h) {
                    val v = vTop * (1f - y.toFloat() / (h - 1))
                    px[y * w + x] = Color.HSVToColor(floatArrayOf(hue, s, v))
                }
            }
            bmp!!.setPixels(px, 0, w, 0, 0, w, h)
        }

        override fun onDraw(canvas: Canvas) {
            bmp?.let { canvas.drawBitmap(it, 0f, 0f, paint) }
            val cx = sat * width
            // 纵向是"0~该列上限"的相对位置，所以手柄要按上限归一化
            val vTop = EventColors.maxValueForWhite(hue, sat).coerceAtLeast(0.05f)
            val cy = (1f - valx / vTop) * height
            canvas.drawCircle(cx, cy.coerceIn(0f, height.toFloat()), 12f, handle)
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE -> {
                    sat = (event.x / width).coerceIn(0f, 1f)
                    // 反解纵向：v = 该列上限 × (1 − y/h)，于是**怎么拖都拖不出白字读不清的颜色**
                    val vTop = EventColors.maxValueForWhite(hue, sat).coerceAtLeast(0.05f)
                    valx = (vTop * (1f - (event.y / height).coerceIn(0f, 1f))).coerceIn(0f, vTop)
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
                    // ⚠️ 必须**先写回自己的 hue** 再 invalidate：
                    //    之前只调 onChange + invalidate，而 hue 字段还是旧值 ——
                    //    于是白色的竖条指示器纹丝不动（主人 2026-09-21 反馈"颜色条上的
                    //    竖条指示不随操作实时更新"）。
                    hue = 360f * (event.x / width).coerceIn(0f, 1f)
                    onChange(hue)
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
