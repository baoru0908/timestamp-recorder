package com.timestamp.recorder

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import com.timestamp.recorder.databinding.ActivityStatsBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 统计页：概览 + 各事件记录数柱状图 + 近 14 天趋势（自绘 Canvas，零依赖） */
class StatsActivity : BaseActivity() {

    companion object {
        const val EXTRA_FOCUS_EVENT_ID = "focus_event_id"
        private const val DAY_MS = 86_400_000L
    }

    private lateinit var binding: ActivityStatsBinding
    private lateinit var repo: EventRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupChrome(binding.toolbar, binding.appBar, binding.root, R.string.stats_title, showBack = true, scrollContent = binding.scrollContent)
        // 液态玻璃顶栏（与主页同源）：内容滚动时从玻璃底下穿过实时折射
        installLiquidTopGlass(binding.topGlass, binding.appBar, binding.scrollContent)
        repo = EventRepository(this)

        val focusId = intent.getLongExtra(EXTRA_FOCUS_EVENT_ID, -1L)
        val events = if (focusId >= 0) repo.getEvents().filter { it.id == focusId } else repo.getEvents()

        val countOf: (TimestampEvent) -> Int = { if (it.isInterval) repo.intervalCount(it.id) else repo.recordCount(it.id) }
        val totalEvents = events.size
        val totalRecords = events.sumOf { countOf(it) }

        binding.tvTotalEvents.text = totalEvents.toString()
        binding.tvTotalRecords.text = totalRecords.toString()

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

        binding.chartEvents.setData(events.map { BarItem(it.name, countOf(it), it.color) })
        binding.chartDays.setData(computeDaily(events))

        bindDurationStats(events)

        binding.tvNoData.visibility = if (totalRecords == 0) View.VISIBLE else View.GONE
    }

    private fun bindDurationStats(events: List<TimestampEvent>) {
        val intervals = events.filter { it.isInterval }.flatMap { repo.getIntervals(it.id) }
        if (intervals.isEmpty()) {
            binding.durationSection.visibility = View.GONE
            return
        }
        val total = intervals.sumOf { it.duration() }
        val avg = total / intervals.size
        val longest = intervals.maxOf { it.duration() }
        binding.durationSection.visibility = View.VISIBLE
        binding.tvDurationSummary.text = buildString {
            append("共 ").append(intervals.size).append(" 段\n")
            append("总时长：").append(TimeFormat.duration(total)).append('\n')
            append("平均：").append(TimeFormat.duration(avg)).append('\n')
            append("最长一次：").append(TimeFormat.duration(longest))
        }
    }

    private fun computeDaily(events: List<TimestampEvent>): List<BarItem> {
        // 14 根柱子横向很挤，只标「日」（1..31，最多两位）保证不截断；
        // 具体是最近 14 天，由区块标题说明
        val dayFmt = SimpleDateFormat("d", Locale.getDefault())
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val starts = (13 downTo 0).map { i ->
            (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -i) }.timeInMillis
        }
        val counts = MutableList(14) { 0 }
        for (e in events) {
            val points = if (e.isInterval) repo.getIntervals(e.id).map { it.start } else repo.getRecords(e.id)
            for (ts in points) {
                for (k in starts.indices) {
                    val end = if (k + 1 < starts.size) starts[k + 1] else starts[k] + DAY_MS
                    if (ts >= starts[k] && ts < end) { counts[k]++; break }
                }
            }
        }
        return starts.mapIndexed { i, t -> BarItem(dayFmt.format(java.util.Date(t)), counts[i], 0xFF1E88E5.toInt()) }
    }
}
