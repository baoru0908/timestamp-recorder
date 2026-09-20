package com.timestamp.recorder

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.DynamicColors

/**
 * 所有页面的公共基座：
 * - 动态取色（M3 Dynamic Colors）
 * - 沉浸式状态栏 / 导航栏（通杀各品牌，含小米 HyperOS 手势条）
 * - 统一的工具栏（返回键 + 标题）与 WindowInsets 处理
 * - 全局应用 MiSans 字体（若已放入 assets/fonts，否则回退系统字体）
 *
 * 子类约定：onCreate 中第一行调用 super.onCreate(savedInstanceState)，
 * 随后 setContentView，最后调用 [setupChrome]。
 */
abstract class BaseActivity : AppCompatActivity() {

    /**
     * @param scrollContent 本页的滚动容器（NestedScrollView / RecyclerView）。
     *   传入后「导航栏高度」会加到它自己的底部留白上，于是内容能一路滚到小白条底下 ——
     *   这才是真正的沉浸；不传则退回「根布局吃导航栏内边距」的保守做法（内容不跑到手势条下）。
     */
    protected fun setupChrome(
        toolbar: MaterialToolbar,
        appBar: View?,
        root: View,
        titleRes: Int,
        showBack: Boolean = false,
        scrollContent: View? = null
    ) {
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(titleRes)
        if (showBack) {
            toolbar.setNavigationIcon(R.drawable.ic_back)
            // 返回键是纯图标按钮：必须给无障碍标签，否则读屏软件只播报"按钮"
            toolbar.navigationContentDescription = getString(R.string.action_back)
            toolbar.setNavigationOnClickListener { finish() }
        } else {
            toolbar.navigationIcon = null
        }

        // 顶部：状态栏内边距只加在 AppBar 上（没有 AppBar 的页面退化为工具栏本身），
        // 让工具栏底色一路铺到屏幕顶端 —— 既不留白、状态栏也被罩住。
        // ⚠️ 顶部内边距只能在这一处加：根布局若再加一次，就会出现「双倍留白」。
        val topHost = appBar ?: toolbar
        ViewCompat.setOnApplyWindowInsetsListener(topHost) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            if (v.paddingTop != top) v.updatePadding(top = top)
            insets
        }

        // 底部（沉浸的关键）：
        // - 有 scrollContent 时，根布局不吃内边距，改把导航栏高度加进滚动容器自己的
        //   底部留白 —— 内容可以滚到小白条下面，最后一项也仍然完整可见
        //   （各页滚动容器都带 clipToPadding=false）。
        // - 没有时退回保守做法：根布局吃导航栏内边距，内容不铺到手势条下，但也不会被遮住。
        // 所有分支都「值变了才写」，避免在 inset 回调里反复 requestLayout 引起布局死循环。
        scrollHost = scrollContent
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            navInsetBottom = nav
            if (scrollHost != null) {
                // 底部留白的唯一计算点（base + extra + nav），页面不碰绝对值
                syncScrollBottomPadding()
                if (v.paddingTop != 0 || v.paddingBottom != 0) v.setPadding(0, 0, 0, 0)
            } else {
                if (v.paddingTop != 0 || v.paddingBottom != nav) v.setPadding(0, 0, 0, nav)
            }
            insets
        }

        // 全局应用 MiSans（无字体文件时自动回退系统字体，零风险）
        Fonts.applyTo(root)
    }

    /** 本页的滚动容器（由 [setupChrome] 记下，作为底部留白的唯一宿主） */
    private var scrollHost: View? = null
    /** 滚动容器最初的底部留白（-1 = 尚未采样） */
    private var scrollBaseBottom = -1
    /** 页面临时需要的额外底部让位（如批量操作栏浮出） */
    private var scrollExtraBottom = 0
    /** 最近一次拿到的导航栏 inset */
    private var navInsetBottom = 0

    /**
     * 底部让位的**唯一计算点**：base（页面自带留白）+ extra（页面临时声明）+ nav（导航栏 inset）。
     *
     * ⚠️ 为什么必须集中在这里：曾经页面自己 `updatePadding(bottom = 140)` 直接覆盖绝对值，
     * 退出批量管理时又把 padding 设回 24（还是**裸像素**，≈6dp）—— 结果导航栏 inset 被一起冲掉，
     * 列表最后一条永久停在系统手势条下面、点不到「复制」（2026-09-21 设计评审 I-2 真机实证）。
     * 现在页面只允许声明"额外需要多少"，绝对值由这里统一算。
     */
    private fun syncScrollBottomPadding() {
        val sc = scrollHost ?: return
        if (scrollBaseBottom < 0) scrollBaseBottom = sc.paddingBottom
        val want = scrollBaseBottom + scrollExtraBottom + navInsetBottom
        if (sc.paddingBottom != want) sc.updatePadding(bottom = want)
    }

    /**
     * 页面声明「额外需要让出多少底部空间」（例如批量操作栏浮出时传 clearance 高度）。
     * 传 0 即恢复基础留白。**不要**再自己调 updatePadding(bottom = ...)，否则会丢掉导航栏 inset。
     */
    protected fun setScrollExtraBottomPadding(extra: Int) {
        if (scrollExtraBottom == extra) return
        scrollExtraBottom = extra
        // 采样基准：页面可能在 inset 到达前就调用（onCreate 里），此时 paddingBottom 仍是 XML 值
        if (scrollBaseBottom < 0) scrollBaseBottom = scrollHost?.paddingBottom ?: 0
        syncScrollBottomPadding()
    }

    /**
     * 大屏内容列居中（平板 / 折叠屏展开 / 横屏）：把「屏宽 − content_max_width」的一半
     * 补成左右内边距，于是内容自然收成居中的一列，行宽不再被拉长到 800dp+。
     *
     * - `content_max_width = 0`（手机，见 values/dimens.xml）时本方法是**空操作** ——
     *   手机观感与改动前逐像素一致；
     * - sw600dp 下为 640dp（见 values-sw600dp/dimens.xml）；
     * - 用运行时偏移而不是复制一套 layout-sw600dp，是为了让布局保持**单一事实源**：
     *   以后改一次布局，手机与大屏同时生效。
     *
     * @param view 需要居中的内容容器（RecyclerView / NestedScrollView 里的内容列）
     * @param baseHorizontalPadding 该容器在手机上的基准左右留白（px）
     */
    protected fun centerContentColumn(view: View, baseHorizontalPadding: Int) {
        val maxWidth = resources.getDimensionPixelSize(R.dimen.content_max_width)
        if (maxWidth <= 0) return
        val apply = Runnable {
            val extra = ((view.width - maxWidth) / 2).coerceAtLeast(0)
            val want = baseHorizontalPadding + extra
            if (view.paddingLeft != want || view.paddingRight != want) {
                view.setPadding(want, view.paddingTop, want, view.paddingBottom)
            }
        }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply.run() }
        view.post(apply)
    }

    /**
     * 二级页面统一的液态玻璃顶栏（与主页同源，风格统一）。
     *
     * ⚠️ 结构铁律：[topGlass] 必须是采样源 [content] 的**兄弟层**（XML 里不能放进 content
     * 内部）—— 每帧录制时 content.draw() 会把玻璃自己画进 RenderNode，形成「显示列表
     * 包含自己」的无限递归 → RenderThread 栈溢出 SIGSEGV（主页踩过）。
     *
     * - [content] 的顶部让位 = appBar 实际高度（含状态栏 inset）+ [contentBaseTop]，
     *   内容滚动时从玻璃底下穿过（NestedScrollView / RecyclerView 需 clipToPadding=false）；
     * - API < 33 液态玻璃不可用：topGlass 隐藏，页面退回普通顶栏（让位照旧，不压内容）。
     */
    protected fun installLiquidTopGlass(
        topGlass: com.qmdeve.liquidglass.widget.LiquidGlassView,
        appBar: View,
        content: View,
        contentBaseTop: Int = 0
    ) {
        appBar.viewTreeObserver.addOnGlobalLayoutListener(object :
            android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val h = appBar.height
                if (h <= 0) return
                val lp = topGlass.layoutParams
                if (lp.height != h) {
                    lp.height = h
                    topGlass.layoutParams = lp
                }
                val want = contentBaseTop + h
                if (content.paddingTop != want) content.updatePadding(top = want)
            }
        })
        // 顶栏的"确定的底"：无论玻璃可不可用，AppBar 都有一层实→半→全透的遮罩，
        // 保证标题行在任何滚动位置都压得住下面的内容（评审 V-1；API<33 时它就是唯一的底，见 I-3）
        appBar.setBackgroundResource(R.drawable.bg_top_scrim)

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                // 玻璃参数（模糊 / 折射 / 着色）统一由 Glass 装配。
                // 二级页顶栏用 solid = true（近黑 + 72% 厚膜，配上面那层遮罩把标题钉死）；
                // 主页不传 solid —— 那里要的是薄玻璃的液态观感（见 MainActivity）。
                Glass.apply(
                    view = topGlass,
                    context = this,
                    content = content as android.view.ViewGroup,
                    cornerRadiusDp = 0f,
                    refractionRes = R.dimen.glass_refraction_top,
                    solid = true
                )
            } catch (_: Throwable) { }
        } else {
            // API < 33：液态玻璃不可用 → 退回「普通玻璃」（比上面那层遮罩更实：平铺 + 下沿分隔线）。
            // ⚠️ 必须真的给一层底：AppBar 自己的背景是 transparent，若这里只把玻璃藏掉，
            //    顶栏就变成完全透明，滚动时列表内容会从标题下面穿过、与标题文字重叠（评审 I-3）。
            topGlass.visibility = android.view.View.GONE
            appBar.setBackgroundResource(R.drawable.bg_top_fallback)
        }
    }

    /**
     * 沉浸式：状态栏 / 导航栏透明（通杀各品牌，含小米 HyperOS 手势条）。
     *
     * ⚠️ **这里刻意不用 AndroidX 的 `enableEdgeToEdge(statusBarStyle = ...)`**：
     * 1. `SystemBarStyle.auto(TRANSPARENT, TRANSPARENT)` 是靠 **scrim 亮度**判断要不要点亮
     *    `APPEARANCE_LIGHT_STATUS_BARS` 的，全透明亮度为 0 → 判定"背景不亮" → 不点亮；
     * 2. 换成 `light(...)` / `dark(...)` 也**依然无效**（实测时间仍是纯白、与背景同色 250）；
     * 3. 它内部会注册一个 `EdgeToEdgeCallback`，**每次分发 insets 都重设一次** appearance，
     *    于是事后手动设的、主题里的 `windowLightStatusBar` 全都会被它冲掉。
     *
     * 结论：绕开它，**自己手动搭沉浸**（`setDecorFitsSystemWindows(false)` + 两条栏刷透明 +
     * 关对比度强制），再用 `WindowCompat.getInsetsController` 直接点亮 appearance ——
     * 没有那个会自我覆盖的 callback，设了就生效。
     */
    private fun applyEdgeToEdge() {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        // 沉浸：内容铺到系统栏底下
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        // 关掉对比度强制：否则透明的两条栏会被 ROM 糊上一层 scrim（底部小白条就是这么来的）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            } catch (_: Exception) {
            }
        }
        // 挖孔屏 / 刘海屏：允许内容延伸进刘海区。之前靠 AndroidX enableEdgeToEdge 顺手设了，
        // 改成手动沉浸后这行必须自己补 —— 不然在打孔机型上状态栏那一条会留黑边。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            try {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams
                        .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            } catch (_: Exception) {
            }
        }
        // 图标配色：日间要深色（顶栏是浅玻璃，白图标会完全看不见），夜间要浅色
        WindowCompat.getInsetsController(window, window.decorView)?.apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 用户切了深浅色：StatusBarStyle 是"固定 light / 固定 dark"的，得重设一次
        applyEdgeToEdge()
    }

    /**
     * 用系统浏览器打开外链。
     *
     * App 自身不申请 INTERNET 权限，联网由系统浏览器负责——保持「零权限」。
     * 设备上没有浏览器（或链接被系统策略拦截）时给个提示，不至于点了没反应。
     */
    protected fun openExternalUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.toast_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        applyEdgeToEdge()
    }
}
