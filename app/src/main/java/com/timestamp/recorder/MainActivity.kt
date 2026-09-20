package com.timestamp.recorder

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.timestamp.recorder.databinding.ActivityMainBinding
import com.timestamp.recorder.databinding.DialogEditEventBinding
import androidx.viewpager2.widget.ViewPager2
import com.timestamp.recorder.databinding.ViewPageEventsBinding
import com.timestamp.recorder.databinding.ViewPageTimelineBinding
import com.timestamp.recorder.databinding.ItemEventBinding
import com.timestamp.recorder.databinding.ItemTimelineCapBinding
import com.timestamp.recorder.databinding.ItemTimelineMonthBinding
import com.timestamp.recorder.databinding.ItemOverflowRowBinding
import com.timestamp.recorder.databinding.ItemTimelineRecordBinding
import com.timestamp.recorder.databinding.ViewOverflowMenuBinding
import java.util.Calendar
import java.util.Collections
import java.util.Locale
import kotlin.math.abs

/**
 * 主页：底部「事件 / 时间线」双 Tab（参考 Last Time 布局）。
 * - 事件 Tab：事件卡片列表 + 快捷记录 + 拖拽排序
 * - 时间线 Tab：全部记录按月份分组（事件色圆点 + 事件名 + 具体时间 + 相对时间）
 * - 右下「＋」上移至 Tab 栏上方，位置可在设置页切换（左/中/右）
 */
class MainActivity : BaseActivity() {

    companion object {
        private const val TAB_EVENTS = 0
        private const val TAB_TIMELINE = 1
        private const val TYPE_MONTH = 0
        private const val TYPE_RECORD = 1
        private const val TYPE_CAP = 2
        private const val TYPE_INTERVAL = 3
        /** 底栏形态：布局内的静态胶囊 / 独立窗口 + 系统级背后模糊 */


        /** 外部（如详情页菜单）指定直接打开时间线 Tab */
        const val EXTRA_OPEN_TIMELINE = "extra_open_timeline"

        /** 「⋮」菜单的动作 id（options menu 与顶部玻璃栏的 PopupMenu 共用） */
        private const val MENU_SETTINGS = 1
        private const val MENU_STATS = 2
        private const val MENU_TUTORIAL = 3
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var pageEvents: ViewPageEventsBinding
    private lateinit var pageTimeline: ViewPageTimelineBinding
    private lateinit var repo: EventRepository
    private val adapter = EventAdapter()
    private val timelineAdapter = TimelineAdapter()
    private var dragEnabled = false
    private var currentTab = TAB_EVENTS

    private val touchHelper by lazy {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                adapter.move(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled() = false
            override fun isItemViewSwipeEnabled() = false
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                adapter.persistOrder()
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupChrome(binding.toolbar, binding.appBar, binding.root, R.string.main_title, showBack = false)
        repo = EventRepository(this)

        // 两个页面（事件 / 时间线）各自 inflate，交给 ViewPager2 做跟手横滑
        pageEvents = ViewPageEventsBinding.inflate(layoutInflater)
        pageTimeline = ViewPageTimelineBinding.inflate(layoutInflater)
        binding.viewPager.adapter = PageAdapter()
        // ⚠️ 必须显式设 1：默认值(OFFSCREEN_PAGE_LIMIT_DEFAULT=-1)下相邻页要等开始滑动
        //    才绑定渲染 —— 滑动第一帧相邻页是空白的，元素「不实时出现」的元凶（资料查证）。
        //    显式 1 = 相邻页从一开始就常驻渲染，滑动立刻露出真实内容。
        binding.viewPager.offscreenPageLimit = 1
        // 桌面式纯平移：两页并排、间距是多少就是多少，1:1 跟手滑动。
        // ⚠️ 不加任何视差/alpha/缩放 transformer —— 相邻页元素「实时出现」由
        //    offscreenPageLimit=1（相邻页常驻渲染）保证，任何附加变换都是画蛇添足（踩过两次）。
        binding.viewPager.registerOnPageChangeCallback(pageCallback)

        pageEvents.recyclerEvents.layoutManager = LinearLayoutManager(this)
        pageEvents.recyclerEvents.adapter = adapter
        touchHelper.attachToRecyclerView(pageEvents.recyclerEvents)

        pageTimeline.recyclerTimeline.layoutManager = LinearLayoutManager(this)
        pageTimeline.recyclerTimeline.adapter = timelineAdapter

        binding.fabAdd.setOnClickListener { showEditDialog(null) }
        // 记下布局里原本的留白：顶部玻璃栏开启 / 关闭时要来回切换


            (pageEvents.tvEmpty.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        applyFabPosition()
        setupBottomBar()
        setupInLayoutGlass()
        // 顶栏装配：⋮ 监听 + 顶部让位（restoreTopPadding）。⚠️ 此函数曾被清理脚本弄丢调用，
        // 症状：右上角 ⋮ 无响应 + 事件首卡被玻璃压住 —— 修复于 2026-09-15。
        syncTopBar()
        styleTab()
        // 状态栏配色由 Activity 主窗口控制（布局内液态玻璃，不建任何浮窗 → 黑化永不被夺）。
        updateStatusBarAppearance()
        // 若从详情页菜单直达时间线 Tab，需在渲染后生效
        if (intent.getBooleanExtra(EXTRA_OPEN_TIMELINE, false)) {
            selectTab(TAB_TIMELINE)
        }
    }

    /**
     * ViewPager2 的页面适配器：只有两页（事件 / 时间线），内容是预构建的视图，不做事。
     * offscreenPageLimit 默认 1，两页互相都保留在层级里，不会发生回收重建。
     */
    private inner class PageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 2
        override fun getItemViewType(position: Int) = position
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = if (viewType == TAB_EVENTS) pageEvents.root else pageTimeline.root
            view.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            return object : RecyclerView.ViewHolder(view) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
    }

    /** ViewPager2 → 底栏的联动：滑动逐帧推滑块，落页后同步选中态与各控件可见性 */
    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(position: Int, positionOffset: Float, offsetPx: Int) {
            moveSlider(position + positionOffset)
            // 桌面式平移：两页内容 1:1 跟手，划到一半时左右各露一半、组件完整实时可见。
            // ⚠️ 刻意不做任何 alpha/视差叠加 —— 那会把进入页「往回推」+ 半透明，
            //    破坏主人要的「和桌面两页平移一样」的自然过渡（实测踩过）。
        }
        override fun onPageSelected(position: Int) {
            if (currentTab != position) {
                currentTab = position
                styleTab()
                updateVisibility()
            }
        }
    }

    /**
     * 底部导航栏（**静态玻璃** + 彻底沉浸）。
     *
     * ⚠️ 这里刻意**不做实时模糊**。Android 没有「模糊身后内容」的公开 API，
     * 想看到背后内容只能自己「截屏 → 模糊 → 回填」；而那条路必然要重绘整棵视图树，
     * 且截到的永远是**上一帧** —— 结果就是延迟肉眼可见 + 滚动掉帧，无论怎么加压都追不上
     * 内容（已实测两版，均如此）。所以整块放弃，改用静态半透明玻璃：零延迟、零额外开销。
     *
     * 沉浸做法：
     * - 根布局四周 padding 恒为 0（顶部内边距由 BaseActivity 加在 AppBar 上）；
     * - bottomBar 高度 = 64dp + 导航栏 inset，一直铺到屏幕最底，系统手势条浮在玻璃之上；
     * - Tab 内容层按导航栏高度加底部 padding，文字绝不被手势条遮挡。
     */
    /** 布局内玻璃（试验版统一形态）：顶部额头与底部岛都是 LiquidGlassView 液态玻璃
     *  （MIT, API33+）：每帧把 mainHost 内容录进 RenderNode，AGSL 折射 + 色散 + GPU 高斯
     *  —— 同窗口捕获不建浮窗，黑化不受影响；API<33 时透明（4.0.0 统一降级）。 */
    private fun setupInLayoutGlass() {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        val d = resources.displayMetrics.density

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                // 底部胶囊岛：完整胶囊圆角 28dp
                binding.bottomBar.bind(binding.mainHost)
                binding.bottomBar.setCornerRadius(28f * d)
                binding.bottomBar.setBlurRadius((14f * d).coerceAtMost(50f))
                binding.bottomBar.setRefractionHeight(20f * d)
                // 顶部额头：贴屏幕顶的玻璃条，不做圆角（液态玻璃自带边缘光效），
                // 折射高度稍收（列表从底下穿过，折射带太宽会顶到标题）
                binding.topGlass.bind(binding.mainHost)
                binding.topGlass.setCornerRadius(0f)
                binding.topGlass.setBlurRadius((14f * d).coerceAtMost(50f))
                binding.topGlass.setRefractionHeight(16f * d)
                // tab 栏中间的加号玻璃圆钮：与岛同材质，风格统一
                binding.fabGlass.bind(binding.mainHost)
                binding.fabGlass.setCornerRadius(26f * d)
                binding.fabGlass.setBlurRadius((12f * d).coerceAtMost(50f))
                binding.fabGlass.setRefractionHeight(12f * d)
                // 色调：浅色模式薄白雾、深色模式深蓝黑雾
                val (tr, tg, tb, ta) = if (night) {
                    listOf(0.12f, 0.12f, 0.18f, 0.38f)
                } else {
                    listOf(1f, 1f, 1f, 0.12f)
                }
                for (v in listOf(binding.bottomBar, binding.topGlass)) {
                    v.setTintColorRed(tr)
                    v.setTintColorGreen(tg)
                    v.setTintColorBlue(tb)
                    v.setTintAlpha(ta)
                }
            } catch (_: Throwable) { }
        }

        // 顶部玻璃高度匹配 AppBar（含状态栏区域）。
        // ⚠️ 不给 viewPager 加 padding —— ViewPager2 的 clipToPadding=false 不可靠，
        // 会让列表在 padding 区被裁掉，玻璃底下永远空白（折射了个寂寞）。
        // 列表全高从屏幕顶铺到屏底，让位统一走 restoreTopPadding（RecyclerView topPadding）。
        binding.appBar.viewTreeObserver.addOnGlobalLayoutListener(object :
            android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val h = binding.appBar.height
                if (h > 0) {
                    val lp = binding.topGlass.layoutParams
                    if (lp.height != h) {
                        lp.height = h
                        binding.topGlass.layoutParams = lp
                    }
                    // AppBar 高度就绪后补一次让位（onCreate 时高度未量出，用的是 dimen 兜底值）
                    restoreTopPadding()
                }
            }
        })
    }

    /** 状态栏配色：主窗口控制，浅色深色图标、深色白图标 */
    private fun updateStatusBarAppearance() {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            ?.isAppearanceLightStatusBars = !night
    }

    private fun setupBottomBar() {
        val immersive = object : androidx.core.view.OnApplyWindowInsetsListener {
            override fun onApplyWindowInsets(
                v: android.view.View,
                insets: androidx.core.view.WindowInsetsCompat
            ): androidx.core.view.WindowInsetsCompat {
                // 顶部内边距由 BaseActivity 加在 AppBar 上（状态栏被工具栏罩住）；
                // 这里只把「导航栏高度」交给底部栏，根布局四周 padding 保持 0。
                val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
                v.setPadding(0, 0, 0, 0)
                applyBarLayout(nav)
                return insets
            }
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root, immersive)
        androidx.core.view.ViewCompat.requestApplyInsets(binding.root)
        // 双保险：post 后 insets 已就绪，手动触发一次（小米 ROM insets 分发时序不可靠）
        binding.root.post {
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(binding.root)
            if (insets != null) {
                immersive.onApplyWindowInsets(binding.root, insets)
            } else {
                binding.root.setPadding(0, 0, 0, 0)
                applyBarLayout(0)
            }
        }
        syncBarMode()
    }

    /**
     * 底栏形态：系统能给模糊就给（胶囊岛放进独立窗口，模糊交给系统合成器，App 端零开销、
     * 真·实时）；给不了就是布局内的**半透明静态胶囊**（完全不模糊）。
     *
     * ⚠️ 刻意**不做**两件事：
     * 1. App 自己截屏 / 着色器算模糊的兜底 —— 用户关掉高级材质（多半为了省电、流畅）时
     *    还硬糊一层，等于无视系统级的显示偏好；
     * 2. 跟随「材质风格」（柔光玻璃 / 轻透磨砂）—— 实测该差异不在系统模糊层
     *    （SurfaceFlinger 输出逐字节相同），是 MIUI 内部 `MaterialToken` 实现的，
     *    第三方 App 没有公开接口；硬跟只能自己编透明度/半径，反而离"系统自己的渲染"更远。
     *
     * 因为「高级材质」是**系统设置**，用户可能在我们退到后台时改，所以 onResume 会再调一次。
     */
    private fun syncBarMode() {
        // 液态玻璃统一形态：布局内 Tab 绑定 + 布局对齐（旧「独立窗口/分形态」分支已随 4.0.0 移除）
        tabEventsRef = layoutTabEvents
        tabTimelineRef = layoutTabTimeline
        bindSlider(binding.tabSlider)
        tabEventsRef?.root?.setOnClickListener { selectTab(TAB_EVENTS) }
        tabTimelineRef?.root?.setOnClickListener { selectTab(TAB_TIMELINE) }
        applyBarLayout(navInsetPx)
        styleTab()
    }

    /** 底栏当前形态（-1 = 尚未定过，首次必然进入初始化分支） */


    // ---------------- 顶部玻璃栏（独立窗口 + 系统级模糊） ----------------

    /**
     * 顶部工具栏也做成「模糊的半透材质」。
     *
     * 难点：系统只能模糊**窗口背后**的内容，而列表和工具栏在同一个窗口里 —— 同窗内的内容
     * 系统没法替我们糊。所以和底部胶囊岛一样，把这一栏搬进一个**独立窗口**（浮在内容之上），
     * 模糊交给系统合成器；同时把布局里的 AppBar 收起来，列表于是会一直铺到屏幕顶端，
     * 滚动时内容就从玻璃栏底下穿过去。
     *
     * 附带影响：右上角「⋮」（设置 / 统计 / 教程）跟着搬进这个窗口 —— 菜单本身也是一扇
     * 独立的玻璃窗口（见 [showOverflowMenu]），动作仍走 [handleMenuAction]，
     * 与原来的 options menu 共用一套逻辑。
     */
    private fun syncTopBar() {
        // 液态玻璃统一形态：布局内 AppBar + topGlass（玻璃配置见 setupInLayoutGlass）
        binding.appBar.visibility = View.VISIBLE
        binding.topGlass.visibility = View.VISIBLE
        binding.btnMore.setOnClickListener { showOverflowMenu(it) }
        restoreTopPadding()
    }




    /** 时间线起笔（Cap）空行的高度 = 列表让位（AppBar 高 + 呼吸感）：
     *  彩线从 Cap 顶贯穿到屏幕最顶端（衬在玻璃底下），Cap 底部正好与事件第一卡对齐。 */
    private var timelineLeadPx = 0

    /** 布局内（液态玻璃试验形态）的列表让位：
     *  列表**全高**从屏幕顶铺到屏底（内容滚动时自然穿过额头玻璃底下，实时被折射）。
     *  事件首卡让位 = AppBar 高 + 呼吸感；时间线让位**恒为 0**（起笔 Cap 顶到屏幕顶，
     *  彩线贯穿玻璃底下），起笔节点对齐交给 Cap 空行高度（timelineLeadPx）。 */
    private fun restoreTopPadding() {
        val h = binding.appBar.height.takeIf { it > 0 }
            ?: resources.getDimensionPixelSize(R.dimen.top_bar_height)
        val gap = resources.getDimensionPixelSize(R.dimen.space_2)
        if (binding.viewPager.paddingTop != 0) binding.viewPager.setPadding(0, 0, 0, 0)
        pageEvents.recyclerEvents.applyTopPadding(h + gap)
        pageTimeline.recyclerTimeline.applyTopPadding(0)
        applyEmptyTopPadding(h + gap * 3)
        val lead = h + gap
        if (timelineLeadPx != lead) {
            timelineLeadPx = lead
            // Cap 高度依赖该值：让位变化后重绑一次（时间线列表条目少，开销可忽略）
            timelineAdapter.notifyDataSetChanged()
        }
    }

    private fun applyEmptyTopPadding(px: Int) {
        for (v in listOf(pageEvents.tvEmpty, pageTimeline.tvEmptyTimeline)) {
            (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                if (lp.topMargin != px) {
                    lp.topMargin = px
                    v.layoutParams = lp
                }
            }
        }
    }

    private fun View.applyTopPadding(px: Int) {
        if (paddingTop != px) setPadding(paddingLeft, px, paddingRight, paddingBottom)
    }

    /**
     * 右上角「⋮」的菜单。
     *
     * ⚠️ 刻意**不用**系统 PopupMenu：
     * 1. 它的位置是按「锚点所在窗口」推算的，而这里的锚点在一扇 NO_LIMITS 的独立窗口里 ——
     *    实测菜单会整个甩到屏幕外面去；
     * 2. 系统那套白底 / 直角 / 无图标的样式，跟我们这套玻璃语言根本不是一个东西。
     *
     * 所以自己来：一块同样跑在**独立窗口**里的玻璃卡片（模糊照样交给系统合成器，App 端零开销），
     * 位置按「按钮在屏幕上的真实坐标」算并**夹在屏幕内**，从按钮那一角缩放淡入。
     * 动作仍走 [handleMenuAction]，和 options menu 共用一套逻辑。
     */
    private fun showOverflowMenu(anchor: View) {
        dismissOverflowMenu()
        val dlg = android.app.Dialog(this, R.style.Theme_Timestamp_Dialog)
        val b = ViewOverflowMenuBinding.inflate(layoutInflater)
        dlg.setContentView(b.root)
        dlg.setCancelable(true)
        dlg.setCanceledOnTouchOutside(true)
        dlg.setOnDismissListener { tintMenuAnchor(anchor, false) }

        bindMenuRow(b.rowSettings, R.drawable.ic_menu_settings, R.string.menu_settings, MENU_SETTINGS)
        bindMenuRow(b.rowStats, R.drawable.ic_menu_stats, R.string.menu_stats, MENU_STATS)
        bindMenuRow(b.rowTutorial, R.drawable.ic_menu_tutorial, R.string.menu_tutorial, MENU_TUTORIAL)

        // 位置：右边缘贴着按钮、整体夹在屏幕内（8dp 安全边距），绝不顶出画面
        val m = resources.getDimensionPixelSize(R.dimen.space_2)
        val menuW = resources.getDimensionPixelSize(R.dimen.overflow_menu_width)
        val maxX = (resources.displayMetrics.widthPixels - menuW - m).coerceAtLeast(m)
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val x = (loc[0] + anchor.width - menuW + m).coerceIn(m, maxX)
        // 上沿就贴在「⋮」下沿下面 6dp —— 视觉上是从按钮里长出来的（缩放原点也在右上角）。
        // 之前挂在「玻璃栏下沿」，中间还隔着一行副标题的落差，看着就远了；
        // 现在 ⋮ 垂直居中于整块栏，它的下沿离栏底只剩几 dp，所以直接锚按钮就又近又不压字。
        val y = (loc[1] + anchor.height +
                resources.getDimensionPixelSize(R.dimen.overflow_menu_gap)).coerceAtLeast(m)

        dlg.window?.let { w ->
            w.setDimAmount(0f)
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // 不吃焦点、不拦窗口外的触摸；WATCH_OUTSIDE_TOUCH 让「点空白处」也能收起菜单
            // ⚠️ LAYOUT_IN_SCREEN 不能少：少了它，窗口被限制在「应用可用区」里，
            //    于是 lp.y 被当成「内容区坐标」—— 设 y=365，实际却被摆到 534
            //    （正好多一条 169px 的状态栏），菜单就凭空往下掉了一截。
            //    顶部玻璃栏那扇窗一直有这个 flag，所以只有菜单踩到。
            w.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
            // 模糊区域由「窗口背景」推导，所以这块可见的玻璃必须给到窗口上（内容本身不带背景）
            val bg = resources.getDrawable(R.drawable.bg_overflow_menu, theme)
            w.setBackgroundDrawable(bg)
            w.setGravity(Gravity.TOP or Gravity.START)
            val lp = w.attributes
            // ⚠️ 宽度必须显式给像素，不能靠布局里的 layout_width：
            //    `Dialog.setContentView(View)` 会把这个 View 挂到 decor 的 FrameLayout 下，
            //    LinearLayout 的 LayoutParams 不兼容 → 被换成 wrap_content，188dp 直接失效
            //    （实测菜单只有 144dp 宽）。
            lp.width = menuW
            lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
            lp.x = x
            lp.y = y
            // ⚠️ 和顶部玻璃栏同一个坑：浮动窗口默认会按状态栏再内缩一次 ——
            //    设了 y=365，实际 frame 却被推到 534（差的正是 169px 状态栏高度），
            //    于是菜单凭空往下掉了一整条状态栏，看着就跟按钮"离得太远"。
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                lp.fitInsetsTypes = 0
                lp.fitInsetsSides = 0
            }
            w.attributes = lp
        }

        // 从右上角那颗按钮的方向展开（宽度已知，不必等测量）
        b.root.pivotX = menuW.toFloat()
        b.root.pivotY = 0f
        b.root.alpha = 0f
        b.root.scaleX = 0.88f
        b.root.scaleY = 0.88f
        dlg.show()
        b.root.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(190L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        overflowDialog = dlg
        tintMenuAnchor(anchor, true)
    }

    private fun bindMenuRow(
        row: ItemOverflowRowBinding,
        icon: Int,
        label: Int,
        action: Int
    ) {
        row.rowIcon.setImageResource(icon)
        row.rowText.setText(label)
        row.rowRoot.setOnClickListener {
            dismissOverflowMenu()
            handleMenuAction(action)
        }
    }

    /** 菜单展开时把「⋮」点亮（主题色），收起后回到常规颜色 —— 让人知道菜单是从哪冒出来的 */
    private fun tintMenuAnchor(anchor: View, open: Boolean) {
        (anchor as? android.widget.ImageView)?.imageTintList =
            ColorStateList.valueOf(if (open) menuAccent else menuIconTint)
    }

    private fun dismissOverflowMenu() {
        overflowDialog?.dismiss()
        overflowDialog = null
    }

    private var overflowDialog: android.app.Dialog? = null

    private val menuIconTint: Int by lazy {
        com.google.android.material.color.MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnSurface)
    }
    private val menuAccent: Int by lazy {
        com.google.android.material.color.MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorPrimary)
    }

    private fun handleMenuAction(id: Int): Boolean = when (id) {
        MENU_SETTINGS -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        MENU_STATS -> { startActivity(Intent(this, StatsActivity::class.java)); true }
        MENU_TUTORIAL -> { startActivity(Intent(this, TutorialActivity::class.java)); true }
        else -> false
    }


    private var topBarRoot: View? = null
    private var topBarLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    /** 顶部玻璃栏是否生效（-1 = 尚未定过） */


    // 布局里原本的留白，退出玻璃顶栏（如系统关掉高级材质）时要还原








    /**
     * 胶囊岛：高度固定，只调整「离底部多远」= 导航栏高度 + 12dp 呼吸感。
     * ⚠️ 直接改 layoutParams 字段不会触发重新布局，必须整体写回；而 inset 回调里无条件写回
     * 又会引起布局死循环，所以统一「数值变了才写」。
     */
    private fun applyBarLayout(nav: Int) {
        navInsetPx = nav
        // 胶囊岛：高度固定，只调整「离底部多远」= 导航栏高度 + 12dp 呼吸感
        val lp = binding.bottomBar.layoutParams as android.view.ViewGroup.MarginLayoutParams
        val want = nav + resources.getDimensionPixelSize(R.dimen.island_bottom_gap)
        if (lp.bottomMargin != want) {
            lp.bottomMargin = want
            binding.bottomBar.layoutParams = lp
        }
        applyFabPosition()
    }

    private var navInsetPx = 0

    /** 一个 Tab 的三件套：容器（承载选中态的玻璃胶囊）、图标、文字 */
    private class TabViews(val root: View, val icon: ImageView, val text: TextView)

    /** 布局内（静态底栏）的两个 Tab —— 从玻璃窗口切回来时要还原成它们 */
    private val layoutTabEvents: TabViews by lazy {
        TabViews(binding.tabEvents, binding.iconEvents, binding.textEvents)
    }
    private val layoutTabTimeline: TabViews by lazy {
        TabViews(binding.tabTimeline, binding.iconTimeline, binding.textTimeline)
    }

    /** 当前生效的两个 Tab（可能在布局里，也可能在玻璃窗口里） */
    private var tabEventsRef: TabViews? = null
    private var tabTimelineRef: TabViews? = null

    /** 当前生效形态里的选中态滑块（切 Tab 时在两个 Tab 之间平移的那块玻璃胶囊） */
    private var tabSliderRef: View? = null

    /** 让 [slider] 成为当前生效的滑块，并挂上「布局一变就重新就位（不带动画）」的监听 */
    private fun bindSlider(slider: View) {
        tabSliderRef = slider
    }

    /** 当前底部栏实测高度（= 内容高 + 导航栏 inset），FAB 据此上移 */


    override fun onResume() {
        super.onResume()
        refresh()
        // 用户可能在系统里改了「高级材质」开关或「材质风格」，回到前台时重新对齐
        syncBarMode()
        applyFabPosition()
        WidgetRecordHelper.refreshAll(this)
        startTickerIfNeeded()
    }

    override fun onPause() {
        stopTicker()
        // 「⋮」菜单是个独立窗口，Activity 退到后台时它不会被自动收掉，这里手动关
        dismissOverflowMenu()
        super.onPause()
    }

    // ---------- 进行中区间的秒级跳动（仅在前台、且确有进行中区间时跑） ----------
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private var ticking = false
    private val tickRunnable = object : Runnable {
        override fun run() {
            // 只重绑事件列表，让「进行中 N」的时长刷新；空转时自动停
            val hasOngoing = repo.getEvents().any { it.isInterval && repo.ongoingInterval(it.id) != null }
            if (hasOngoing) {
                adapter.notifyDataSetChanged()
                ticker.postDelayed(this, 1000)
            } else {
                ticking = false
            }
        }
    }

    private fun startTickerIfNeeded() {
        val hasOngoing = repo.getEvents().any { it.isInterval && repo.ongoingInterval(it.id) != null }
        if (hasOngoing && !ticking) {
            ticking = true
            ticker.post(tickRunnable)
        } else if (!hasOngoing) {
            stopTicker()
        }
    }

    private fun stopTicker() {
        ticking = false
        ticker.removeCallbacks(tickRunnable)
    }

    override fun onDestroy() {
        // 独立窗口要收掉，避免窗口泄漏
        dismissOverflowMenu()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // 系统栏图标配色，按当前深浅色**强制**定死，不吃 ROM 默认值：
            // - 状态栏：顶栏是浅色玻璃，浅色模式下图标必须转深（否则白图标糊在浅玻璃上看不见）；
            // - 手势条：深色模式下强制深色 pill，融入深色玻璃，彻底沉浸。
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                        Configuration.UI_MODE_NIGHT_YES
                val navAppear = if (night) {
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                } else 0
                val statusAppear = if (night) {
                    0
                } else {
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                }
                window.insetsController?.setSystemBarsAppearance(
                    navAppear or statusAppear,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                        or android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
            }
            // 窗口 insets 在此后才完全就绪：强制应用一次沉浸布局
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(binding.root)
            if (insets != null) {
                val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
                binding.root.setPadding(0, 0, 0, 0)
                applyBarLayout(nav)
                // 状态栏高度此时才是准的：顶部玻璃栏的上内边距 / 列表留白要重算
                restoreTopPadding()
            }
        }
    }

    /** 快捷按钮位置：左 / 中 / 右（设置页可切换）；底边距跟随底部栏实测高度，始终悬在栏上方 */
    private fun applyFabPosition() {
        // 加号玻璃圆钮对齐「岛的正中心」：fabGlass 在 root 层（标准 FrameLayout，
        // gravity 才生效——LiquidGlassView 自定义 onLayout 会忽略子 View gravity），
        // 底边距 = 岛底边距 + (岛高 - 圆钮高) / 2，正好与胶囊岛同心。
        val lp = binding.fabGlass.layoutParams as android.widget.FrameLayout.LayoutParams
        val want = navInsetPx + resources.getDimensionPixelSize(R.dimen.island_bottom_gap) +
                (resources.getDimensionPixelSize(R.dimen.tab_bar_height) -
                        resources.getDimensionPixelSize(R.dimen.fab_glass_size)) / 2
        if (lp.bottomMargin != want) {
            lp.bottomMargin = want
            binding.fabGlass.layoutParams = lp
        }
    }

    private fun currentSortMode(): String =
        getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
            .getString(SettingsActivity.KEY_SORT_MODE, SettingsActivity.SORT_MANUAL) ?: SettingsActivity.SORT_MANUAL

    // ---------- 底部 Tab 切换（ViewPager2 跟手横滑 + 滑块逐帧联动） ----------
    // 页面切换完全交给 ViewPager2：跟手、可急停、可反向拖回都是它的标准能力，
    // 自己再写 OnItemTouchListener 反而会和列表滚动打架（试过，删了）。

    private val tabPrimary: Int by lazy {
        com.google.android.material.color.MaterialColors.getColor(
            binding.tabEvents, com.google.android.material.R.attr.colorPrimary)
    }
    private val tabOnSurfaceVariant: Int by lazy {
        com.google.android.material.color.MaterialColors.getColor(
            binding.tabEvents, com.google.android.material.R.attr.colorOnSurfaceVariant)
    }

    private fun selectTab(tab: Int) {
        // 落页后 onPageSelected 会同步选中态；这里只管把页面平滑滚过去
        if (binding.viewPager.currentItem != tab) {
            binding.viewPager.currentItem = tab
        }
    }

    /** 选中 Tab = 文字/图标提亮 + 滑块滑到该 Tab（滑块位置由 ViewPager2 联动驱动）。 */
    private fun styleTab() {
        val eventsSelected = currentTab == TAB_EVENTS
        val ev = tabEventsRef ?: layoutTabEvents
        val tl = tabTimelineRef ?: layoutTabTimeline
        setTabLook(ev, eventsSelected)
        setTabLook(tl, !eventsSelected)
        moveSlider(currentTab.toFloat())
    }

    /**
     * 选中态的玻璃胶囊由**滑块 View** 统一承载（压在两个 Tab 内容的下层）：
     * 切 Tab 时它 220ms 平滑滑过去，而不是背景瞬间跳变 —— 这是底部栏唯一的动画，
     * 也是"选中了哪个"最直观的指示。文字/图标颜色仍由这里按选中态切换。
     */
    private fun setTabLook(tab: TabViews, selected: Boolean) {
        tab.root.background = null
        val content = if (selected) {
            val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
            if (night) 0xFFEDEAF3.toInt() else 0xFF16181C.toInt()
        } else {
            tabOnSurfaceVariant
        }
        tab.text.setTextColor(content)
        tab.icon.imageTintList = ColorStateList.valueOf(content)
    }

    /**
     * 滑块位置：由 ViewPager2 的 `onPageScrolled(position, positionOffset)` 逐帧调用，
     * fraction = 当前页 + 滑动进度（0~1 连续）—— 所以滑块是**跟手**的：
     * 手指拖到哪它跟到哪，中途停下它就停（急停），反向拖它就跟回去。
     *
     * 几何与配色全部由 [TabSliderView.onDraw] 内部按 View 实际宽高计算
     * （View 铺满整座岛，胶囊画在 1/2 等分处），不碰任何布局参数 ——
     * 之前「窄 View 平移 + 改 layoutParams.width」在别的密度机型上算错一档
     * 就会把整条岛铺满（真机实测踩过），画出来的永远是对的。
     */
    private fun moveSlider(fraction: Float) {
        (tabSliderRef as? TabSliderView)?.fraction = fraction
    }

    /** 统一管理两个列表与各自空状态的可见性 */
    private fun updateVisibility() {
        val events = currentTab == TAB_EVENTS
        // ⚠️ 两页 RecyclerView **恒为 VISIBLE** —— ViewPager2 平移时相邻页必须实时绘制，
        //    GONE 会让滑动中的相邻页露出空白页（「过渡留白」的元凶，桌面式翻页被它毁了）。
        //    空态提示只在落页时切换显隐。
        pageEvents.tvEmpty.visibility = if (events && adapter.itemCount == 0) View.VISIBLE else View.GONE
        pageTimeline.tvEmptyTimeline.visibility =
            if (!events && timelineAdapter.itemCount == 0) View.VISIBLE else View.GONE
        // 时间线底轨在时间线页内部，跟着页面走，无需代码切换
    }

    // ---------- 菜单（设置 + 统计 + 教程；时间线走底部 Tab） ----------
    // ⚠️ 不再注册 options menu：那会在工具栏上多出一颗系统样式的「⋮」（跟自定义按钮重复，
    //    关掉高级材质时两个 ⋮ 并排出现）。统一走 showOverflowMenu() 的玻璃菜单。

    private fun refresh() {
        dragEnabled = currentSortMode() == SettingsActivity.SORT_MANUAL
        val events = if (dragEnabled) repo.getEventsManualOrder() else repo.getEventsByRecent()
        adapter.submit(events)
        timelineAdapter.submit((repo.getAllRecords().map { Entry.Point(it) } + repo.getAllIntervals().map { Entry.Interval(it) }).sortedByDescending { it.millis })
        updateVisibility()
    }

    private fun quickRecord(event: TimestampEvent) {
        if (event.isInterval) {
            // 区间事件：点一下 = 开始；已在进行中 = 结束
            val ongoing = repo.ongoingInterval(event.id)
            if (ongoing == null) {
                val start = repo.startInterval(event.id)
                Toast.makeText(this, getString(R.string.toast_started, TimeFormat.hm(start)), Toast.LENGTH_SHORT).show()
            } else {
                val ended = repo.stopInterval(event.id)
                val dur = ended?.duration() ?: 0
                Toast.makeText(this, getString(R.string.toast_stopped, event.name, TimeFormat.duration(dur)), Toast.LENGTH_SHORT).show()
            }
            startTickerIfNeeded()
        } else {
            repo.addRecord(event.id)
            Toast.makeText(this, getString(R.string.toast_recorded, event.name), Toast.LENGTH_SHORT).show()
        }
        refresh()
        WidgetRecordHelper.refreshAll(this)
    }

    private fun showEditDialog(event: TimestampEvent?) {
        val dlg = DialogEditEventBinding.inflate(layoutInflater)
        val colorAdapter = ColorAdapter(event?.color ?: EventColors.random())
        dlg.recyclerColors.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 6)
        dlg.recyclerColors.adapter = colorAdapter
        if (event != null) dlg.editName.setText(event.name)

        // 记录方式选择：点时刻（默认）/ 时间段。编辑已有事件时回填其类型。
        val initialType = event?.type ?: TimestampEvent.TYPE_POINT
        dlg.chipPoint.isChecked = initialType == TimestampEvent.TYPE_POINT
        dlg.chipInterval.isChecked = initialType == TimestampEvent.TYPE_INTERVAL
        fun typeHintFor(type: Int) {
            dlg.tvTypeHint.setText(
                if (type == TimestampEvent.TYPE_INTERVAL) R.string.event_type_interval_hint
                else R.string.event_type_point_hint
            )
        }
        typeHintFor(initialType)
        dlg.chipPoint.setOnCheckedChangeListener { _, checked -> if (checked) typeHintFor(TimestampEvent.TYPE_POINT) }
        dlg.chipInterval.setOnCheckedChangeListener { _, checked -> if (checked) typeHintFor(TimestampEvent.TYPE_INTERVAL) }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (event == null) R.string.dialog_add_event_title else R.string.dialog_edit_event_title)
            .setView(dlg.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dlg.editName.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    dlg.inputName.error = getString(R.string.toast_name_required)
                    return@setOnClickListener
                }
                val type = if (dlg.chipInterval.isChecked) TimestampEvent.TYPE_INTERVAL else TimestampEvent.TYPE_POINT
                if (event == null) repo.addEvent(name, colorAdapter.selected, type)
                else repo.updateEvent(event.id, name, colorAdapter.selected, type)
                dialog.dismiss()
                refresh()
                WidgetRecordHelper.refreshAll(this@MainActivity)
            }
        }
        dialog.show()
    }

    private fun showEventMenu(event: TimestampEvent) {
        MaterialAlertDialogBuilder(this)
            .setTitle(event.name)
            .setItems(arrayOf(getString(R.string.menu_edit_event), getString(R.string.menu_delete_event))) { _, which ->
                when (which) {
                    0 -> showEditDialog(event)
                    1 -> confirmDeleteEvent(event)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteEvent(event: TimestampEvent) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_event_title)
            .setMessage(getString(R.string.delete_event_msg, event.name, repo.recordCount(event.id)))
            .setPositiveButton(R.string.delete) { _, _ ->
                repo.deleteEvent(event.id)
                WidgetRecordHelper.refreshAll(this)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openDetail(event: TimestampEvent) {
        startActivity(Intent(this, EventDetailActivity::class.java)
            .putExtra(EventDetailActivity.EXTRA_EVENT_ID, event.id))
    }

    // ---------- 事件列表适配器（支持拖拽重排） ----------

    private inner class EventAdapter : RecyclerView.Adapter<EventAdapter.VH>() {

        val items = mutableListOf<TimestampEvent>()

        fun submit(list: List<TimestampEvent>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        fun move(from: Int, to: Int) {
            if (from == to || from !in items.indices || to !in items.indices) return
            Collections.swap(items, from, to)
            notifyItemMoved(from, to)
        }

        fun persistOrder() {
            repo.setEventsOrder(items.map { it.id })
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        inner class VH(private val b: ItemEventBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(event: TimestampEvent) {
                b.tvEventName.text = event.name
                b.viewColorDot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(event.color)
                }

                if (event.isInterval) {
                    // 区间事件：显示进行中状态 / 次数与上次时长
                    val ongoing = repo.ongoingInterval(event.id)
                    val intervals = repo.getIntervals(event.id)
                    b.tvEventCount.text = if (ongoing != null) "■" else getString(R.string.btn_start)
                    b.ivPlus.visibility = View.GONE
                    // 进行中：按钮染红（停止感）；空闲：事件色
                    val runningColor = if (ongoing != null) 0xFFD32F2F.toInt() else event.color
                    b.btnQuickRecord.backgroundTintList = ColorStateList.valueOf(runningColor)
                    b.tvEventInfo.text = if (ongoing != null) {
                        getString(R.string.detail_ongoing, TimeFormat.duration(ongoing.duration()))
                    } else if (intervals.isNotEmpty()) {
                        val last = intervals.first()
                        getString(R.string.interval_last, TimeFormat.duration(last.duration()))
                    } else {
                        getString(R.string.event_no_record)
                    }
                } else {
                    // 点事件：原有行为
                    val count = repo.recordCount(event.id)
                    val last = repo.lastRecord(event.id)
                    b.tvEventInfo.text = if (last != null) {
                        getString(R.string.event_last, TimeFormat.hm(last))
                    } else {
                        getString(R.string.event_no_record)
                    }
                    b.tvEventCount.text = count.toString()
                    b.ivPlus.visibility = View.VISIBLE
                    b.btnQuickRecord.backgroundTintList = ColorStateList.valueOf(event.color)
                }

                b.btnQuickRecord.setOnClickListener { quickRecord(event) }
                b.root.setOnClickListener { openDetail(event) }
                b.btnMenu.setOnClickListener { showEventMenu(event) }

                if (dragEnabled) {
                    b.btnDrag.visibility = View.VISIBLE
                    b.btnDrag.setOnTouchListener { _, e ->
                        if (e.action == MotionEvent.ACTION_DOWN) touchHelper.startDrag(this)
                        false
                    }
                } else {
                    b.btnDrag.visibility = View.GONE
                }
            }
        }
    }

    // ---------- 时间线适配器（月份分组标题 + 记录行） ----------

    /** 月份分组键（按本地时区的年 + 月） */
    private data class MonthKey(val year: Int, val month: Int) {
        val label: String get() = String.format(Locale.getDefault(), "%d年%d月", year, month)

        companion object {
            fun of(millis: Long): MonthKey {
                val cal = Calendar.getInstance()
                cal.timeInMillis = millis
                return MonthKey(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
            }
        }
    }

    /** 时间线统一条目：点记录与区间都按 start 时刻参与月份分组与排序 */
    private sealed class Entry {
        data class Point(val rec: TimelineRecord) : Entry()
        data class Interval(val iv: TimelineInterval) : Entry()
        val millis: Long get() = when (this) { is Point -> rec.millis; is Interval -> iv.start }
        val color: Int get() = when (this) { is Point -> rec.eventColor; is Interval -> iv.eventColor }
        val eventId: Long get() = when (this) { is Point -> rec.eventId; is Interval -> iv.eventId }
    }

    private sealed class TimelineItem {
        data class Month(val key: MonthKey, val count: Int) : TimelineItem()
        data class Record(val rec: TimelineRecord) : TimelineItem()
        data class Interval(val iv: TimelineInterval) : TimelineItem()

        /** 起笔 / 收笔：列表最上、最下那一段「有颜色的空行」，让时间线的两头不至于没颜色 */
        data class Cap(val color: Int, val head: Boolean) : TimelineItem()
    }

    private inner class TimelineAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = mutableListOf<TimelineItem>()

        /**
         * 每个列表项的「代表色」，与 [items] 一一对应：
         * 记录 = 该记录事件的颜色；月份标题 = **该月首条记录**的颜色
         * （这样月份行和它下面的记录同色，不会在月份处突然换个颜色）。
         */
        private var colors = IntArray(0)

        fun submit(entries: List<Entry>) {
            items.clear()
            // 已按时间倒序；LinkedHashMap 保持「新月份在前」的插入顺序
            val monthMap = LinkedHashMap<MonthKey, MutableList<Entry>>()
            for (e in entries) {
                val key = MonthKey.of(e.millis)
                monthMap.getOrPut(key) { mutableListOf() }.add(e)
            }
            for ((key, list) in monthMap) {
                items.add(TimelineItem.Month(key, list.size))
                list.forEach {
                    items.add(when (it) {
                        is Entry.Point -> TimelineItem.Record(it.rec)
                        is Entry.Interval -> TimelineItem.Interval(it.iv)
                    })
                }
            }
            // 从后往前推：月份取紧随其后那条记录的颜色
            var cols = IntArray(items.size)
            for (i in items.indices.reversed()) {
                cols[i] = when (val it = items[i]) {
                    is TimelineItem.Record -> it.rec.eventColor
                    is TimelineItem.Interval -> it.iv.eventColor
                    is TimelineItem.Month -> if (i + 1 < items.size) cols[i + 1] else 0
                    is TimelineItem.Cap -> it.color
                }
            }
            // 两头各补一段起笔 / 收笔：颜色沿用「最新那条」与「最旧那条」
            if (cols.isNotEmpty()) {
                items.add(0, TimelineItem.Cap(cols.first(), head = true))
                items.add(TimelineItem.Cap(cols.last(), head = false))
                cols = IntArray(cols.size + 2).also {
                    it[0] = cols.first()
                    System.arraycopy(cols, 0, it, 1, cols.size)
                    it[it.size - 1] = cols.last()
                }
            }
            colors = cols
            notifyDataSetChanged()
        }

        /** 上一条的颜色（用于渐变过渡）；没有上一条 → null（顶部淡入） */
        private fun prevColor(position: Int): Int? =
            if (position > 0 && colors[position - 1] != 0) colors[position - 1] else null

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is TimelineItem.Month -> TYPE_MONTH
            is TimelineItem.Record -> TYPE_RECORD
            is TimelineItem.Interval -> TYPE_INTERVAL
            is TimelineItem.Cap -> TYPE_CAP
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_MONTH -> MonthVH(ItemTimelineMonthBinding.inflate(inflater, parent, false))
                TYPE_CAP -> CapVH(ItemTimelineCapBinding.inflate(inflater, parent, false))
                TYPE_INTERVAL -> IntervalVH(ItemTimelineRecordBinding.inflate(inflater, parent, false))
                else -> RecordVH(ItemTimelineRecordBinding.inflate(inflater, parent, false))
            }
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is TimelineItem.Month -> (holder as MonthVH).bind(item, position)
                is TimelineItem.Record -> (holder as RecordVH).bind(item.rec, position)
                is TimelineItem.Interval -> (holder as IntervalVH).bind(item.iv, position)
                is TimelineItem.Cap -> (holder as CapVH).bind(item, position)
            }
        }

        inner class CapVH(private val b: ItemTimelineCapBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: TimelineItem.Cap, position: Int) {
                b.root.layoutParams = b.root.layoutParams.apply {
                    // 起笔高度跟顶栏形态走：独立玻璃窗口罩着屏幕顶端时要够高才能从玻璃下穿出来；
                    // 静态 AppBar 那种情况 AppBar 自己已占着顶端，起笔只需一小段把线接上。
                    height = when {
                        !item.head -> resources.getDimensionPixelSize(R.dimen.timeline_tail_height)
                        // 液态玻璃形态：起笔空行高度 = 列表让位（AppBar 高 + 呼吸感）——
                        // 彩线从屏幕最顶贯穿下来（衬在玻璃底下），Cap 底部正好与事件第一卡对齐
                        timelineLeadPx > 0 -> timelineLeadPx
                        else -> resources.getDimensionPixelSize(R.dimen.timeline_head_height_static)
                    }
                }
                b.vLine.setLine(
                    prevColor(position),
                    if (item.color != 0) item.color else colors.getOrElse(position) { 0 },
                    if (item.head) TimelineLineView.Mode.HEAD else TimelineLineView.Mode.TAIL
                )
            }
        }

        inner class MonthVH(private val b: ItemTimelineMonthBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: TimelineItem.Month, position: Int) {
                b.tvMonth.text = item.key.label
                b.tvMonthCount.text = getString(R.string.timeline_month_count, item.count)
                // 竖线：顶部一小段内从上一条的颜色过渡到自己的（月份行也不再是灰色）
                val own = colors[position]
                b.vLine.setLine(prevColor(position), own)
                // 刻度点：该月颜色（半透明，弱于记录节点）；无颜色时退回次要色
                val dim = com.google.android.material.color.MaterialColors.getColor(
                    b.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
                b.vDot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (own != 0) withAlpha(own, 0x99) else dim)
                }
            }
        }

        inner class RecordVH(private val b: ItemTimelineRecordBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(rec: TimelineRecord, position: Int) {
                b.tvEventName.text = rec.eventName
                b.tvTime.text = TimeFormat.short(rec.millis)
                b.tvRelative.text = TimeFormat.relative(this@MainActivity, rec.millis)
                // 竖线：顶部一小段内从上一条的颜色过渡到本条的，条与条之间不再硬切
                b.vLine.setLine(prevColor(position), rec.eventColor)
                // 节点：本记录的事件色（实心，作为「这一刻」的标记）
                b.vDot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(rec.eventColor)
                }
                b.root.setOnClickListener { openDetail(repo.getEvent(rec.eventId) ?: return@setOnClickListener) }
            }
        }

        inner class IntervalVH(private val b: ItemTimelineRecordBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(iv: TimelineInterval, position: Int) {
                b.tvEventName.text = iv.eventName
                // 开始 – 结束（时长）；进行中显示已用时
                val endStr = iv.end?.let { TimeFormat.short(it) } ?: getString(R.string.timeline_ongoing, TimeFormat.duration(iv.durationMillis))
                b.tvTime.text = "${TimeFormat.short(iv.start)} – $endStr（${TimeFormat.duration(iv.durationMillis)}）"
                b.tvRelative.text = TimeFormat.relative(this@MainActivity, iv.start)
                b.vLine.setLine(prevColor(position), iv.eventColor)
                b.vDot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(iv.eventColor)
                }
                b.root.setOnClickListener { openDetail(repo.getEvent(iv.eventId) ?: return@setOnClickListener) }
            }
        }
    }
}
