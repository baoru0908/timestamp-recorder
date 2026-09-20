package com.timestamp.recorder

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import com.timestamp.recorder.databinding.ViewGlassMenuBinding

/**
 * 展开 / 收起共用的动画时长（毫秒）。
 *
 * ⚠️ 展开与收起**必须用同一个值**：时长不一致，形状再对称也会觉得"哪里不对"。
 */
private const val MENU_ANIM_MS = 190L

/**
 * 收起时的落点缩放（= 展开时的起点缩放）：同一条轨迹上原路往返，别用另一组数。
 */
private const val MENU_SCALE_EDGE = 0.88f

/**
 * 统一的**玻璃菜单**（⋮ 按钮 / 长按呼出的选单）。
 *
 * ## 为什么不直接用系统菜单
 * 系统 `PopupMenu` / 工具栏溢出菜单 / `AlertDialog.setItems` 都是白底（或纯深灰）直角、
 * 系统字重、系统动效 —— 与本 App「液态玻璃 + 圆角卡片」的语言完全不是一个东西。
 * 所以统一自绘：一块跑在独立窗口里的玻璃卡片（模糊交给系统合成器），
 * 从锚点那一角缩放淡入，位置按「锚点在屏幕上的真实坐标」计算并夹在屏幕内。
 *
 * ## 统一到这一处的样式规范
 * | 维度 | 规范 |
 * |---|---|
 * | 容器 | 独立窗口 + `bg_overflow_menu`（圆角 20dp + `glass_stroke` 描边 + `glass_menu` 底色） |
 * | 宽度 | `glass_menu_width` = 188dp（写死，便于算出"不顶出屏幕"的位置） |
 * | 内边距 | 卡片内 8dp；每行左右 14/18dp |
 * | 行高 | 48dp（≥48dp 触摸目标） |
 * | 图标 | 22dp，色 = `colorOnSurfaceVariant`；危险项 = `colorError` |
 * | 文字 | 15sp / `colorOnSurface`；危险项 = `colorError` |
 * | 展开 | 190ms：aplha 0→1、scale 0.88→1.0，`DecelerateInterpolator`，pivot = 锚点那一角 |
 * | 收起 | 190ms：alpha 1→0、scale 1.0→0.88，`AccelerateInterpolator`（= Decelerate 的时间反演），同一 pivot |
 * | 关闭 | ①再次点击锚点 ②点空白处 ③返回键（由 Activity 接管）→ 三者都走**同一套退场动画** |
 * | 触摸分区 | 窗口是**触摸模态**的：菜单开着时窗口外的触摸由窗口自己吃掉（→ 收起菜单），不穿透到下层控件，因此"再点锚点"与"点空白处"完全等价 |
 *
 * ## 动效为什么要"时间反演"
 * 一次进出场本质上是同一条轨迹的两个方向，所以：
 * - **落点** = 起点的同一组数值（scale 0.88、alpha 0），不是另调一组"看起来差不多"的数；
 * - **缓动** = Decelerate 换成它的时间反演 Accelerate —— Decelerate 是"快起慢收"，
 *   倒放过来就是"慢起快收"，视觉上才像同一段动画往回走；
 * - **时长** = 与展开完全一致（190ms）。
 *
 * ## 关闭路径与"重复点击"的约定
 * - **再次点击同一个锚点** → 收起动画。实现上它和"点空白处"是**同一条路径**：
 *   锚点在菜单窗口之外，所以这一下同样是 ACTION_OUTSIDE → 收起（见 [MenuDialog] 与 addFlags）。
 *   全程不重建窗口、不重播轨迹，因此不会闪。
 * - **点空白处 / 返回键** → 同一个 [Handle.playExit]，交互一致（见 [MenuDialog]）。
 * - **点击某一行** → 立即收起（不播动画），因为动作本身会立刻推出下一层界面，
 *   让菜单再淡出 190ms 会与那层界面叠成一团影子（见 `create()` 里的说明）。
 * - **点到别的锚点** → 旧菜单立即收起、新菜单正常展开：两股动效叠在一起反而更乱。
 *
 * ## 覆盖的场景
 * 1. 主页顶部「⋮」→ 设置 / 统计 / 使用教程
 * 2. 事件卡「⋮」**和事件卡长按** → 编辑事件 / 删除事件（后者危险色）
 * 3. 事件详情页工具栏「⋮」→ 编辑 / 导出 / 清空 / 删除 / 统计 / 时间线（批量管理独立成 ☑ 图标）
 */
object GlassMenu {

    /**
     * 一条菜单项。
     * @param action 调用方自定义的动作 id，回传给 [show] / [toggle] 的 onAction
     * @param destructive 危险动作（删除类）：图标与文字用 `colorError`，和普通项区分开
     */
    class Item(
        val iconRes: Int,
        val labelRes: Int,
        val action: Int,
        val destructive: Boolean = false
    )

    /**
     * 一次菜单的生命周期句柄。
     *
     * 调用方只需拿它做两件事：读 [isOpen]（同步锚点的点亮状态）、在 Activity 退场时 [Activity] 侧
     * 调 [closeNow]。**不要**自己去 `Dialog.dismiss()` —— 那会跳过退场动画。
     */
    class Handle internal constructor(
        private val dialog: Dialog,
        /** 呼出这次菜单的锚点。「再次点击同一个锚点 = 收起」靠它做身份判定 */
        internal val anchor: View,
        /** 菜单所属 Activity（返回键 / onPause 强关时需要） */
        internal val activity: Activity,
        /** 菜单卡片根视图 —— 动画目标就是它（卡片底也挂在它身上，见 create()） */
        private val content: View
    ) {
        internal enum class Phase { OPENING, OPEN, CLOSING }

        internal var phase = Phase.OPENING
        private var destroyed = false

        /** 窗口是否还在（展开动画中 / 已展开 / 退场动画中均算在） */
        val isShowing: Boolean get() = !destroyed

        /**
         * 当前是否"算开着"。退场动画进行中即算**已关闭**。
         *
         * 调用方用它同步锚点的点亮状态：收起一开始就"灭灯"。
         */
        //  （注：菜单窗口是触摸模态的，"收起动画进行中再点锚点"这个场景本身不可达 ——
        //    那一下会被窗口当作"点窗口外"吃掉，只会让收起继续。）

        val isOpen: Boolean get() = !destroyed && phase != Phase.CLOSING

        /** 正向展开：Decelerate（快起慢收） */
        internal fun playEnter() {
            if (destroyed) return
            phase = Phase.OPENING
            animate(alpha = 1f, scale = 1f, interpolator = DecelerateInterpolator()) {
                if (phase == Phase.OPENING) phase = Phase.OPEN
            }
        }

        /** 反向收起：Accelerate（慢起快收，即 Decelerate 的时间反演），落点 = 展开的起点 */
        internal fun playExit() {
            if (destroyed) return
            if (phase == Phase.CLOSING) return
            phase = Phase.CLOSING
            animate(alpha = 0f, scale = MENU_SCALE_EDGE, interpolator = AccelerateInterpolator()) {
                // 动画期间用户可能又点了一次锚点（反向展开）—— 只有仍停在收起态才真正关窗
                if (!destroyed && phase == Phase.CLOSING) destroyNow()
            }
        }

        /** 立即收起（不播动画）：onPause / onDestroy / 换锚点 / 点菜单行时用 */
        internal fun destroyNow() {
            if (destroyed) return
            destroyed = true
            // cancel() 会回调上面那个 endAction；destroyed 已置位 → 它会被挡住，不会重复踩
            content.animate().cancel()
            dialog.dismiss()
        }

        /**
         * 起点/终点由 ViewPropertyAnimator 从**当前值**读取 ——
         * 所以动画中途被打断再反演（收起→展开、展开→收起）都是从当前位置接着走，不会跳。
         */
        private fun animate(alpha: Float, scale: Float, interpolator: Interpolator, end: () -> Unit) {
            content.animate().cancel()
            content.animate()
                .alpha(alpha)
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(MENU_ANIM_MS)
                .setInterpolator(interpolator)
                .withEndAction(end)
                .start()
        }
    }

    /**
     * 玻璃菜单的窗口容器。
     *
     * 存在的唯一理由：把「点空白处」的默认收起行为（`Dialog.cancel()` → 瞬间 dismiss）
     * 换成与展开**对称**的退场动画。否则同一个"关掉菜单"的动作会出现两种观感 ——
     * 再点锚点是平滑收起、点空白处却"啪"一下消失，交互就不一致了。
     *
     * ⚠️ 返回键不走这里：菜单窗口是 `FLAG_NOT_FOCUSABLE`（不能吃焦点，否则会把下方页面的
     *    触摸全挡住），按键根本到不了这个窗口 —— 返回键由 Activity 侧的
     *    [closeAnimated] 接管，两条路径最终都落在同一个 [Handle.playExit]。
     */
    private class MenuDialog(activity: Activity, themeResId: Int) : Dialog(activity, themeResId) {
        var onAnimatedCancel: (() -> Unit)? = null

        override fun cancel() {
            val animated = onAnimatedCancel
            if (animated == null) super.cancel() else animated()
        }
    }

    /** 当前打开的菜单。玻璃菜单是独立窗口，同一时刻只允许存在一个 */
    private var current: Handle? = null

    /**
     * 呼出菜单（点锚点用）：**同一个锚点再点一次就是收起**。
     *
     * 若该锚点的菜单正在播退场动画，则反向展开回来（不重建窗口 → 不闪）。
     * 若开着的是**别的锚点**的菜单，先把它立即收掉再开新的（避免两股动效叠加）。
     *
     * @return 菜单句柄。调用方**必须**在 onPause/onDestroy 里 `GlassMenu.closeNow(this)`
     *         （独立窗口不会随 Activity 自动收掉）
     */
    fun toggle(
        activity: Activity,
        anchor: View,
        items: List<Item>,
        onDismiss: () -> Unit,
        onAction: (Int) -> Unit
    ): Handle = obtain(activity, anchor, items, canClose = true, onDismiss = onDismiss, onAction = onAction)

    /**
     * 只负责展开（长按之类的"用力"手势用）。
     *
     * 长按的语义是"打开这个菜单"，不是"开关"：所以即使同一个锚点的菜单已经开着，
     * 也只保持展开，不会把它收掉（否则长按一下就闪没了）。
     */
    fun open(
        activity: Activity,
        anchor: View,
        items: List<Item>,
        onDismiss: () -> Unit,
        onAction: (Int) -> Unit
    ): Handle = obtain(activity, anchor, items, canClose = false, onDismiss = onDismiss, onAction = onAction)

    private fun obtain(
        activity: Activity,
        anchor: View,
        items: List<Item>,
        canClose: Boolean,
        onDismiss: () -> Unit,
        onAction: (Int) -> Unit
    ): Handle {
        val live = liveFor(anchor)
        if (live != null) {
            // 已展开（含展开动画正播）：点锚点（canClose）→ 收起；长按（只展开）→ 保持打开
            if (live.isOpen && canClose) live.playExit()
            // 已在收起：**什么都不做**，让它安静收完。
            // 这是"再次点击锚点"的兜底：菜单窗口是触摸模态的（点窗口外的那一下会被窗口
            // 自己吃掉并触发退场），正常情况根本轮不到这里；万一某个 ROM 仍把这一下透给
            // 下层页面，这里也只是"维持收起"，绝不会像当初那样又把它展开回来
            // —— 那正是"再点 ⋮ 没有回弹"的成因（见 addFlags 处的长注释）。
            return live
        }
        return create(activity, anchor, items, onDismiss, onAction)
    }

    /**
     * 取回**同一个锚点**上还活着的菜单实例；顺手把"别的锚点"上的旧菜单立即收掉
     * （不同锚点之间不该播动画：一个在收一个在开，两股动效叠着看反而更乱）。
     */
    private fun liveFor(anchor: View): Handle? {
        val cur = current ?: return null
        if (cur.anchor === anchor && cur.isShowing) return cur
        cur.destroyNow()
        return null
    }

    /** 当前菜单是否属于 [activity] 且窗口还在（Activity 侧判断"要不要接管返回键"用） */
    fun isOpenIn(activity: Activity): Boolean {
        val cur = current ?: return false
        return cur.activity === activity && cur.isShowing
    }

    /**
     * 收起 [activity] 的菜单并播放退场动画（返回键用）。
     * @return true = 确实收掉了（调用方应当消费掉这次返回）
     */
    fun closeAnimated(activity: Activity): Boolean {
        val cur = current ?: return false
        if (cur.activity !== activity || !cur.isShowing) return false
        cur.playExit()
        return true
    }

    /** 立即收起、不播动画（onPause / onDestroy）。传 [activity] 则只收属于它的那个 */
    fun closeNow(activity: Activity? = null) {
        val cur = current ?: return
        if (activity != null && cur.activity !== activity) return
        cur.destroyNow()
    }

    private fun create(
        activity: Activity,
        anchor: View,
        items: List<Item>,
        onDismiss: () -> Unit,
        onAction: (Int) -> Unit
    ): Handle {
        val dlg = MenuDialog(activity, R.style.Theme_Timestamp_Dialog)
        val b = ViewGlassMenuBinding.inflate(activity.layoutInflater)
        dlg.setContentView(b.root)
        dlg.setCancelable(true)
        dlg.setCanceledOnTouchOutside(true)

        val handle = Handle(dlg, anchor, activity, b.root)
        // 点空白处 → Dialog.cancel() → 换成同一套退场动画
        dlg.onAnimatedCancel = { handle.playExit() }
        dlg.setOnDismissListener {
            if (current === handle) current = null
            onDismiss()
        }
        current = handle

        // 行：动态 inflate，样式统一在 bindRow 里
        items.forEach { item ->
            val row = LayoutInflater.from(activity)
                .inflate(R.layout.item_overflow_row, b.menuRows, false)
            bindRow(row, item)
            row.setOnClickListener {
                // 点行 = 立即收窗（不播退场动画），再执行动作。
                // 动作几乎都会立刻推出下一层界面（编辑弹窗 / 统计页 / 教程页），
                // 让菜单再花 190ms 淡出，会与那层界面叠出一层"影子"，反而脏。
                handle.destroyNow()
                onAction(item.action)
            }
            b.menuRows.addView(row)
        }

        // 位置：右边缘贴住锚点、整体夹在屏幕内（8dp 安全边距），绝不顶出画面
        val m = activity.resources.getDimensionPixelSize(R.dimen.space_2)
        val menuW = activity.resources.getDimensionPixelSize(R.dimen.glass_menu_width)
        val maxX = (activity.resources.displayMetrics.widthPixels - menuW - m).coerceAtLeast(m)
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val x = (loc[0] + anchor.width - menuW + m).coerceIn(m, maxX)
        val y = (loc[1] + anchor.height +
                activity.resources.getDimensionPixelSize(R.dimen.glass_menu_gap)).coerceAtLeast(m)

        dlg.window?.let { w ->
            w.setDimAmount(0f)
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // 不吃焦点；**窗口外的触摸由本窗口自己吃掉**（不写 FLAG_NOT_TOUCH_MODAL），
            // 再由 WATCH_OUTSIDE_TOUCH 把这一下变成 ACTION_OUTSIDE → 走同一套退场动画。
            //
            // 🔴 这里踩过一个坑（2026-09-21「再点 ⋮ 没有回弹」）：
            //    原先带了 FLAG_NOT_TOUCH_MODAL，于是"点窗口外"的那一下会被**投递两份** ——
            //    一份当作 ACTION_OUTSIDE 给菜单（→ 开始收起），另一份漏给下层的页面
            //    （→ 点到「⋮」按钮 → 又调 toggle() 把菜单展开回来）。两股动效互相抵消，
            //    观感就是"点了没反应"。去掉这个 flag 之后，锚点那一下和空白处那一下
            //    完全等价，都是"点窗口外" → 都走同一条收起路径。
            //    （这也是 Material 菜单的常规做法：菜单打开时，第一下点击只负责关掉菜单，
            //      不会穿透去激活下面的控件。）
            // ⚠️ LAYOUT_IN_SCREEN 不能少：少了它窗口被限制在"应用可用区"，
            //    lp.y 会被当成内容区坐标 → 菜单凭空往下掉一整个状态栏的高度。
            // ⚠️ 与顶部玻璃栏同一个坑：浮动窗口默认按状态栏再内缩一次（用 fitInsets 关掉）。
            w.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
            // ⚠️ 卡片底**必须挂在根视图上**，不能只给窗口背景：
            //    窗口背景不参与 View 的 alpha/scale 动画 —— 那样展开时卡片会"整块凭空出现"、
            //    收起时又会"啪"一下整块消失，动效只剩里面的文字在缩放。
            //    移到根视图后，缩放/淡出作用在整张卡上，进出场才真的是"卡片从锚点角长出来 / 缩回去"。
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            b.root.background = activity.resources.getDrawable(R.drawable.bg_overflow_menu, activity.theme)
            w.setGravity(Gravity.TOP or Gravity.START)
            val lp = w.attributes
            // ⚠️ 宽度必须显式给像素：Dialog.setContentView 会把根 View 挂到 decor 的 FrameLayout 下，
            //    LinearLayout 的 LayoutParams 不兼容 → 被换成 wrap_content，188dp 直接失效。
            lp.width = menuW
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            lp.x = x
            lp.y = y
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                lp.fitInsetsTypes = 0
                lp.fitInsetsSides = 0
            }
            w.attributes = lp
        }

        // 从锚点那一角展开（宽度已知，不必等测量）
        b.root.pivotX = menuW.toFloat()
        b.root.pivotY = 0f
        b.root.alpha = 0f
        b.root.scaleX = MENU_SCALE_EDGE
        b.root.scaleY = MENU_SCALE_EDGE
        dlg.show()
        handle.playEnter()
        return handle
    }

    /** 单行样式：图标 22dp + 文字 15sp；危险项统一换成 colorError，并补一枚同色小圆底衬 */
    private fun bindRow(row: View, item: Item) {
        val icon = row.findViewById<ImageView>(R.id.rowIcon)
        val text = row.findViewById<TextView>(R.id.rowText)
        val tint = MaterialColors.getColor(
            row,
            if (item.destructive) com.google.android.material.R.attr.colorError
            else com.google.android.material.R.attr.colorOnSurfaceVariant,
            0xFF808080.toInt()
        )
        icon.setImageResource(item.iconRes)
        icon.imageTintList = android.content.res.ColorStateList.valueOf(tint)
        text.setText(item.labelRes)
        text.setTextColor(
            MaterialColors.getColor(
                row,
                if (item.destructive) com.google.android.material.R.attr.colorError
                else com.google.android.material.R.attr.colorOnSurface,
                0xFF202020.toInt()
            )
        )
        // 危险项在图标下垫一枚极淡的同色圆底：一眼能看出"这条是破坏性的"
        if (item.destructive) {
            val d = row.resources.displayMetrics.density
            icon.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(withAlpha(tint, 0x24))
            }
            val lp = icon.layoutParams as ViewGroup.MarginLayoutParams
            lp.width = (30 * d).toInt()
            lp.height = (30 * d).toInt()
            icon.layoutParams = lp
            icon.setPadding((7 * d).toInt(), (7 * d).toInt(), (7 * d).toInt(), (7 * d).toInt())
        }
    }
}
