package com.timestamp.recorder

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import com.google.android.material.color.MaterialColors
import com.timestamp.recorder.databinding.ActivityStatsBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 统计页：概览（**点事件 / 区间事件分治**）+ 各事件记录数 + 时间段时长 + 近 N 天趋势
 * （全部自绘 Canvas，零第三方依赖）。
 *
 * 与旧版的关键差异：
 * - KPI 2×2：事件数 / 记录条数（点）/ 时间段数 / 总时长 —— 不再把「条」和「段」相加成一个误导值；
 * - 焦点模式闭环：从某事件详情进入时显示「当前：X · 返回全部」；
 * - 趋势支持 7 / 14 / 30 天切换；
 * - 时长汇总改用既有 string，不再在 Kotlin 里硬编码中文。
 */
class StatsActivity : BaseActivity() {

    companion object {
        const val EXTRA_FOCUS_EVENT_ID = "focus_event_id"
        private const val DAY_MS = 86_400_000L
        private val RANGES = intArrayOf(7, 14, 30)
        private const val DEFAULT_RANGE = 14
    }

    private lateinit var binding: ActivityStatsBinding
    private lateinit var repo: EventRepository

    /** 当前视角下的事件（焦点模式时只有 1 个）—— 趋势切换需要复用 */
    private var events: List<TimestampEvent> = emptyList()
    private var rangeDays = DEFAULT_RANGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupChrome(
            binding.toolbar, binding.appBar, binding.root, R.string.stats_title,
            showBack = true, scrollContent = binding.scrollContent
        )
        installLiquidTopGlass(binding.topGlass, binding.appBar, binding.scrollContent)
        repo = EventRepository(this)

        val focusId = intent.getLongExtra(EXTRA_FOCUS_EVENT_ID, -1L)
        val focus = if (focusId >= 0) repo.getEvent(focusId) else null
        events = if (focus != null) listOf(focus) else repo.getEvents()

        bindFocusBar(focus)
        bindKpis()
        bindBusiest()
        binding.chartEvents.setData(events.map { BarItem(it.name, countOf(it), it.color) })
        bindDuration()
        setupRangeToggle()
        applyChartA11y()

        binding.tvNoData.visibility = if (events.sumOf { countOf(it) } == 0) View.VISIBLE else View.GONE

        // 大屏内容列居中（手机上是空操作）：平板/折叠屏/横屏下 KPI 与图表不再横贯整屏
        centerContentColumn(
            binding.scrollContent.getChildAt(0) as View,
            resources.getDimensionPixelSize(R.dimen.screen_horizontal_padding)
        )
    }

    /**
     * 自绘 Canvas 图表对读屏软件是**一片空白**（没有文本节点），必须补一句人能听懂的话。
     * 与其让它逐个念数字，不如直接给结论："最多的是喝水，共 52 条"（评审 A-3）。
     */
    private fun applyChartA11y() {
        val busiest = events.maxByOrNull { countOf(it) }
        val busiestCount = busiest?.let { countOf(it) } ?: 0
        binding.chartEvents.contentDescription =
            if (busiest == null || busiestCount == 0) {
                getString(R.string.cd_chart_events_empty)
            } else {
                getString(R.string.cd_chart_events, busiest.name, busiestCount, events.size)
            }
    }

    private fun countOf(e: TimestampEvent): Int =
        if (e.isInterval) repo.intervalCount(e.id) else repo.recordCount(e.id)

    /** 焦点条：只在「从某事件详情进入」时出现，给出「当前：X + 返回全部」的闭环。 */
    private fun bindFocusBar(focus: TimestampEvent?) {
        if (focus == null) {
            binding.focusBar.visibility = View.GONE
            return
        }
        binding.focusBar.visibility = View.VISIBLE
        binding.tvFocusName.text = getString(R.string.stats_focus_prefix, focus.name)
        binding.viewFocusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(focus.color)
        }
        binding.btnFocusBack.setOnClickListener { finish() }
        // 焦点模式下「记录最多的事件」恒等于当前事件，冗余 → 隐藏
        binding.busiestCard.visibility = View.GONE
    }

    /** KPI：点事件的「条」与区间事件的「段 / 总时长」分开统计。 */
    private fun bindKpis() {
        val pointRecords = events.filter { !it.isInterval }.sumOf { repo.recordCount(it.id) }
        val intervalCount = events.filter { it.isInterval }.sumOf { repo.intervalCount(it.id) }
        val totalDuration = events.filter { it.isInterval }
            .sumOf { e -> repo.getIntervals(e.id).sumOf { it.duration() } }

        binding.tvKpiEvents.text = events.size.toString()
        binding.tvKpiRecords.text = pointRecords.toString()
        binding.tvKpiIntervals.text = intervalCount.toString()
        binding.tvKpiDuration.text =
            if (totalDuration <= 0) "0" else TimeFormat.duration(this, totalDuration)
    }

    private fun bindBusiest() {
        val busiest = events.maxByOrNull { countOf(it) }
        val busiestCount = busiest?.let { countOf(it) } ?: 0
        if (busiest != null && busiestCount > 0) {
            binding.tvBusiest.text = getString(R.string.event_count_label, busiest.name, busiestCount)
            binding.viewBusiestDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(busiest.color)
            }
        } else {
            binding.tvBusiest.text = getString(R.string.stats_empty_hint)
        }
    }

    /** 时间段时长汇总：段数 / 总时长 / 平均 / 最长 —— 全部走既有 string，不再硬编码。 */
    private fun bindDuration() {
        val intervals = events.filter { it.isInterval }.flatMap { repo.getIntervals(it.id) }
        if (intervals.isEmpty()) {
            binding.durationSection.visibility = View.GONE
            return
        }
        binding.durationSection.visibility = View.VISIBLE
        val total = intervals.sumOf { it.duration() }
        val avg = total / intervals.size
        val longest = intervals.maxOf { it.duration() }
        binding.tvDurationSummary.text = listOf(
            getString(R.string.event_intervals, intervals.size),
            getString(R.string.stats_total_duration) + getString(R.string.stats_kpi_sep) + TimeFormat.duration(this, total),
            getString(R.string.stats_avg_duration) + getString(R.string.stats_kpi_sep) + TimeFormat.duration(this, avg),
            getString(R.string.stats_longest_duration) + getString(R.string.stats_kpi_sep) + TimeFormat.duration(this, longest)
        ).joinToString("\n")
    }

    // ---------- 趋势：近 N 天 + 7 / 14 / 30 切换 ----------

    private fun setupRangeToggle() {
        listOf(binding.btnRange7, binding.btnRange14, binding.btnRange30)
            .forEachIndexed { i, btn ->
                btn.text = getString(R.string.stats_range_days, RANGES[i])
                btn.setOnClickListener { setRange(RANGES[i]) }
            }
        setRange(DEFAULT_RANGE)
    }

    private fun setRange(days: Int) {
        rangeDays = days
        binding.tvTrendTitle.text = getString(R.string.stats_by_day, days)
        val data = computeDaily(days)
        binding.chartDays.setData(data)
        // 同样给趋势图一句摘要（自绘图对读屏软件不可见）
        binding.chartDays.contentDescription =
            if (data.all { it.value == 0 }) {
                getString(R.string.cd_chart_days_empty, days)
            } else {
                getString(R.string.cd_chart_days, days, data.sumOf { it.value }, data.maxOf { it.value })
            }
        val sel = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        val dim = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        listOf(
            binding.btnRange7 to RANGES[0],
            binding.btnRange14 to RANGES[1],
            binding.btnRange30 to RANGES[2]
        ).forEach { (btn, d) ->
            btn.setTextColor(if (d == days) sel else dim)
            // 选中态只靠颜色会让读屏用户与色觉障碍用户都看不出当前口径（评审 A-4）
            btn.isSelected = d == days
        }
    }

    private fun computeDaily(days: Int): List<BarItem> {
        // 柱子多时只标「日」（1..31，最多两位）保证不截断；具体是最近 N 天由区块标题说明
        val dayFmt = SimpleDateFormat("d", Locale.getDefault())
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val starts = ((days - 1) downTo 0).map { i ->
            (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -i) }.timeInMillis
        }
        val counts = MutableList(days) { 0 }
        for (e in events) {
            val points = if (e.isInterval) repo.getIntervals(e.id).map { it.start } else repo.getRecords(e.id)
            for (ts in points) {
                for (k in starts.indices) {
                    val end = if (k + 1 < starts.size) starts[k + 1] else starts[k] + DAY_MS
                    if (ts >= starts[k] && ts < end) { counts[k]++; break }
                }
            }
        }
        return starts.mapIndexed { i, t -> BarItem(dayFmt.format(Date(t)), counts[i], 0xFF1E88E5.toInt()) }
    }
}
