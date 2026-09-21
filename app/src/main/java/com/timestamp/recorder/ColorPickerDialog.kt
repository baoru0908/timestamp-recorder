package com.timestamp.recorder

import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

/**
 * 轻量 HSV 取色器（无第三方库）：顶部色相滑条 + 中间 饱和×明度 面板 + 预览 + 确定。
 * 用于事件色的「自定义色」。
 *
 * v4.7.0 起还有两个变化：
 * 1. 标题下方多了一个**颜色编码输入框**，可以直接键入 `#RRGGBB` 等十六进制值；
 * 2. SV 面板的纵向明度是**完整 0~100%**（不再限制在「白字仍能达标」的上限内），
 *    所以可以选到纯白与高亮浅色 —— 代价见 [SVView] 的类注释。
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
    private lateinit var hueBarView: HueBar
    private lateinit var hexLayout: TextInputLayout
    private lateinit var hexInput: TextInputEditText

    /**
     * 防回环标志：程序化写输入框（拖动色盘 / 色相条时回填）期间置为 true，
     * 让 [TextWatcher] 直接返回，避免「输入框 → 色盘 → 输入框」无限递归。
     */
    private var syncingHex = false

    init {
        // v4.7.0：**不再**用 forWhiteText 夹取初始色 —— 否则打开一个白色事件会被压暗，
        // 与"支持完整亮度范围"直接冲突。直接取原色 HSV。
        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)
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

        // 颜色编码输入框（放在标题下方、预览块上方）
        // ⚠️ 文案直接写在代码里是为了把改动限制在 2 个文件内；下次做多语言时应迁进 strings.xml
        hexLayout = TextInputLayout(context).apply {
            hint = "颜色编码"
            helperText = "支持 #RRGGBB、#RGB（# 可省略），也接受 #AARRGGBB（忽略前两位）"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * d).toInt() }
        }
        hexInput = TextInputEditText(context).apply {
            // 大写字符 + 无联想；回车即应用
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            setTextColor(context.getColor(R.color.md_on_surface))
            contentDescription = "颜色编码输入框，输入十六进制颜色值后回车应用"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = applyHexFromInput(s?.toString())
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    applyHexFromInput(text?.toString())
                    true
                } else {
                    false
                }
            }
        }
        hexLayout.addView(hexInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(hexLayout)

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
        svView.onChange = { s, v -> sat = s; valx = v; updatePreview(); syncHexField() }
        // 自绘面板对读屏软件是空白的，必须自己说清楚它能干什么（评审 A-1）
        svView.contentDescription = context.getString(R.string.cd_color_sv_panel)
        // 尺寸自适应：固定 260dp 在 320dp 窄屏或横屏下会溢出被裁（评审 C-5）。
        // 取「屏宽 − 2×内边距 − 一点呼吸」与 260dp 的较小值。
        val screenW = context.resources.displayMetrics.widthPixels
        val svSize = minOf((260 * d).toInt(), (screenW - pad * 2 - (16 * d).toInt()).coerceAtLeast((160 * d).toInt()))
        root.addView(svView, LinearLayout.LayoutParams(svSize, svSize).apply { bottomMargin = pad / 2 })

        // 色相条
        hueBarView = HueBar(context).apply { hue = this@ColorPickerDialog.hue }
        hueBarView.contentDescription = context.getString(R.string.cd_color_hue_bar)
        // 拖动期间让 SV 面板用低分辨率重建，松手后再补精修（见 SVView.beginHueDrag）
        hueBarView.onDragStart = { svView.beginHueDrag() }
        hueBarView.onDragEnd = { svView.endHueDrag() }
        hueBarView.onChange = { h ->
            hue = h
            // v4.7.0：不再按"白字上限"夹取 valx —— 面板纵向是完整 0~100%，
            // 换色相只改色相，明度保持不变。
            svView.hue = h
            svView.valx = valx
            updatePreview()
            syncHexField()
        }
        root.addView(hueBarView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (28 * context.resources.displayMetrics.density).toInt()
        ).apply { bottomMargin = pad })

        val btn = MaterialButton(context).apply { text = context.getString(R.string.confirm) }
        btn.setOnClickListener {
            // v4.7.0：**不再**用 forWhiteText 夹取 —— 否则输入 #FFFFFF 会被压回中灰。
            // 纯白/高亮底上的文字可读性由 EventColors.onColor() 自动转近黑来兜底。
            onPick(Color.HSVToColor(floatArrayOf(hue, sat, valx)))
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
        // 用当前初始色回填输入框。走 syncingHex 保护，避免触发 TextWatcher 提前访问色盘。
        syncingHex = true
        hexInput.setText(formatHex(currentColor()))
        syncingHex = false
    }

    private fun currentColor(): Int = Color.HSVToColor(floatArrayOf(hue, sat, valx))

    private fun updatePreview() {
        preview.setBackgroundColor(currentColor())
    }

    /** 供输入框显示的 `#RRGGBB` 文本 */
    private fun formatHex(color: Int): String =
        String.format(Locale.US, "#%06X", color and 0xFFFFFF)

    /**
     * 解析用户键入的颜色编码。合法返回 ARGB（alpha 恒为 FF），非法/不完整返回 null。
     *
     * 接受：`#RRGGBB`、`RRGGBB`、`#RGB`（简写，每位翻倍）、`#AARRGGBB`（取后 6 位、忽略 alpha）。
     * 大小写不敏感，首尾空白忽略。
     */
    private fun parseHexColor(raw: String): Int? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("#")) s = s.substring(1)
        if (s.isEmpty()) return null
        if (!s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
        return when (s.length) {
            3 -> {
                // #RGB 简写：每位十六进制数重复一次（F → FF）
                val r = s[0].digitToInt(16) * 17
                val g = s[1].digitToInt(16) * 17
                val b = s[2].digitToInt(16) * 17
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            6 -> 0xFF000000.toInt() or s.toInt(16)
            // #AARRGGBB：只取后 6 位（忽略 alpha），本 App 的事件色恒为不透明
            8 -> 0xFF000000.toInt() or s.substring(2).toInt(16)
            else -> null
        }
    }

    /**
     * 输入框内容变化 → 应用到色盘。
     *
     * 非法或输入到一半（如只敲了 `#F`）时**静默忽略**：不崩溃、不弹错、不改变当前颜色，
     * 也不打断打字。格式提示常驻在输入框下方的 helper 文案里。
     */
    private fun applyHexFromInput(text: String?) {
        if (syncingHex) return
        val argb = parseHexColor(text.orEmpty()) ?: return
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        hue = hsv[0]; sat = hsv[1]; valx = hsv[2]
        svView.hue = hue      // setter 会重建位图并 invalidate
        svView.sat = sat
        svView.valx = valx
        svView.invalidate()
        hueBarView.hue = hue
        updatePreview()
    }

    /** 色盘/色相条变化 → 回填输入框（带防回环标志，并且把光标稳定放到末尾） */
    private fun syncHexField() {
        if (!::hexInput.isInitialized) return
        syncingHex = true
        hexInput.setText(formatHex(currentColor()))
        hexInput.setSelection(hexInput.text?.length ?: 0)
        syncingHex = false
    }

    /**
     * 饱和(横向)×明度(纵向) 面板。
     *
     * ⚠️ **v4.7.0：纵向明度是完整 0~100%**（此前被 `EventColors.maxValueForWhite` 限制在
     * 「白字仍能达标（≥4.5:1）」的上限内，选不到纯白与高亮浅色）。
     *
     * 放开范围的**必然代价**（用户已拍板接受）：选到纯白 / 高亮浅色后，事件卡上的文字由
     * [EventColors.onColor] 自动转成近黑色 —— 也就是 v4.5.0 曾被否掉的「同一列表里黑白字混排」
     * 会重新出现。这是"要完整亮度范围"换来的取舍，不是 bug。
     */
    class SVView(context: Context) : View(context) {
        var hue: Float = 0f
            set(value) {
                field = value
                // ⚠️ 必须**重建位图**而不只是 invalidate：方形面板的像素颜色是烘进 Bitmap 的，
                //    只重绘等于把旧色相再画一遍 —— 表现就是"拖色相条，方板颜色不动"
                //    （主人 2026-09-21 反馈"颜色条与方形颜色板更新不同步"）。
                //    拖动中改用低分辨率（[dragScale]）重建，松手后再补一次全分辨率。
                rebuild(if (dragging) dragScale else 1f)
                invalidate()
            }
        var sat: Float = 1f
        var valx: Float = 1f
        var onChange: (Float, Float) -> Unit = { _, _ -> }

        // ==================== 跟手性：分辨率分级 ====================

        /** 色相条正在被拖 = 用低分辨率位图换跟手性，松手后补精修 */
        private var dragging = false

        /**
         * 拖动时位图的缩放比。
         *
         * 面板在 600dpi（density 3.75）上是 975×975 ≈ **95 万像素**，全量重建要 95 万次
         * `Color.HSVToColor`；1/4 分辨率 → 244×244 ≈ 6 万，工作量降到约 1/16，
         * 松手后再补全分辨率，拖动期间肉眼看不出差别。
         * （⚠️ 估算尺寸一律写 px：按 dp 记会低估一个 density 倍数。）
         */
        private val dragScale = 0.25f

        private var bmp: Bitmap? = null
        /** 像素缓冲：容量够就复用，拖动期不再每帧 new 一个几十万元素的 IntArray */
        private var pxBuf: IntArray? = null
        /** 复用给 `Color.HSVToColor`，避免每个像素都新建一个 FloatArray */
        private val hsvTmp = FloatArray(3)
        private val dstRect = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // ==================== 手柄 ====================

        /** 手柄半径（px）。圆心会被夹在离边缘一个半径以内，保证整圈都落在面板里 */
        private val handleRadius = 12f

        /**
         * 双色描边：外圈半透明深色 + 内圈白色，同一圆心同一半径、内圈后画覆盖中间。
         *
         * v4.7.0 起面板从纯黑到纯白整段明度都有：黑底靠白圈、**白底靠深圈**
         * —— 两层叠起来保证任意底色上都至少有一层能看见。
         */
        private val handleOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 6f
        }
        private val handleInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f
        }

        /** 松手后的补精修：放进 post 队列执行，且能被新的按下手势取消 */
        private val refineRunnable = Runnable { rebuild(1f); invalidate() }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rebuild(1f)
        }

        /** 色相条按下：切到低分辨率，并取消尚未执行的补精修（快速连续拖动时避免白算一次全量） */
        fun beginHueDrag() {
            dragging = true
            removeCallbacks(refineRunnable)
        }

        /** 色相条松手 / 取消：排一次全分辨率补精修 */
        fun endHueDrag() {
            dragging = false
            removeCallbacks(refineRunnable)
            post(refineRunnable)
        }

        /**
         * 生成面板位图：横向 = 饱和度 0~1，纵向 = 明度 **1~0（完整范围）**。
         *
         * @param scale 位图分辨率比例：拖动时传 [dragScale]，其余时候传 1f。
         */
        private fun rebuild(scale: Float) {
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            val bw = (w * scale).toInt().coerceAtLeast(2)
            val bh = (h * scale).toInt().coerceAtLeast(2)

            // 位图 / 像素缓冲尺寸没变就复用：拖动期不再反复 create（省掉 GC 压力）
            val old = bmp
            if (old == null || old.width != bw || old.height == bh) {
                bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            }
            var px = pxBuf
            if (px == null || px.size < bw * bh) {
                px = IntArray(bw * bh)
                pxBuf = px
            }

            hsvTmp[0] = hue
            for (x in 0 until bw) {
                hsvTmp[1] = x.toFloat() / (bw - 1)
                for (y in 0 until bh) {
                    // v4.7.0：纵向直接铺满 0~100% 明度，不再乘"白字上限"
                    hsvTmp[2] = 1f - y.toFloat() / (bh - 1)
                    px[y * bw + x] = Color.HSVToColor(hsvTmp)
                }
            }
            bmp!!.setPixels(px, 0, bw, 0, 0, bw, bh)
        }

        /**
         * 把手柄圆心夹进面板内，留出一个半径的边距 —— 四个角都不会再露出半截圈。
         *
         * **取舍**：角部的手柄会与手指位置差约 [handleRadius] 像素。这是"手柄在面板内
         * 始终完整可见"优先于"像素级贴合手指"，已确认可接受。
         * 修复前圆心能落在 (0,0)，白圈只有右下 1/4 在面板内，看上去就是"左上角一团白"
         * （主人 2026-09-21 反馈）。
         */
        private fun clampToPanel(v: Float, extent: Float): Float {
            val half = extent / 2f
            val min = handleRadius.coerceAtMost(half)
            val max = (extent - handleRadius).coerceAtLeast(half)
            return v.coerceIn(min, max)
        }

        override fun onDraw(canvas: Canvas) {
            val b = bmp
            if (b != null) {
                // 低分辨率位图在这里被放大铺满（[paint] 带 FILTER_BITMAP）；
                // 松手后会补一张全分辨率的，拖动期间只是略糊，不再掉帧。
                dstRect.set(0f, 0f, width.toFloat(), height.toFloat())
                canvas.drawBitmap(b, null, dstRect, paint)
            }
            val cx = clampToPanel(sat * width, width.toFloat())
            // v4.7.0：纵向是完整 0~100% 明度，手柄直接按 valx 定位，不再按上限归一化
            val cy = clampToPanel((1f - valx) * height, height.toFloat())
            canvas.drawCircle(cx, cy, handleRadius, handleOuter)
            canvas.drawCircle(cx, cy, handleRadius, handleInner)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE -> {
                    sat = (event.x / width).coerceIn(0f, 1f)
                    // v4.7.0：纵向直接映射到完整 0~100% 明度（怎么拖都不会被上限夹住）
                    valx = (1f - (event.y / height).coerceIn(0f, 1f)).coerceIn(0f, 1f)
                    onChange(sat, valx)
                    invalidate()
                    return true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    performClick()
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
        /** 按下通知：让 SV 面板切到低分辨率 */
        var onDragStart: () -> Unit = {}
        /** 松手 / 取消通知：让 SV 面板补一次全分辨率 */
        var onDragEnd: () -> Unit = {}
        private var bmp: Bitmap? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val dstRect = RectF()
        // 原先是每次 onDraw 新建一个 Paint（拖动时每帧一个），提到字段里复用
        private val indicator = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; strokeWidth = 4f
        }

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
            bmp?.let {
                dstRect.set(0f, 0f, width.toFloat(), height.toFloat())
                canvas.drawBitmap(it, null, dstRect, paint)
            }
            val x = hue / 360f * width
            canvas.drawLine(x, 0f, x, height.toFloat(), indicator)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // 先通知，再改 hue —— 否则这一帧的低分辨率开关还没打开，仍会全量重建
                    onDragStart()
                    applyHueFrom(event.x)
                    return true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    applyHueFrom(event.x)
                    return true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    onDragEnd()
                    performClick()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun applyHueFrom(x: Float) {
            // ⚠️ 必须**先写回自己的 hue** 再 invalidate：
            //    之前只调 onChange + invalidate，而 hue 字段还是旧值 ——
            //    于是白色的竖条指示器纹丝不动（主人 2026-09-21 反馈"颜色条上的
            //    竖条指示不随操作实时更新"）。
            hue = 360f * (x / width).coerceIn(0f, 1f)
            onChange(hue)
            invalidate()
        }
    }
}
