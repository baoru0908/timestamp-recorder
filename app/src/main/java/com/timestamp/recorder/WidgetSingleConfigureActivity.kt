package com.timestamp.recorder

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.DynamicColors
import com.timestamp.recorder.databinding.ActivityWidgetConfigureBinding
import com.timestamp.recorder.databinding.ItemWidgetBindBinding

/**
 * 单事件小组件配置页：选择该小组件要绑定的目标事件。
 * 绑定后桌面显示该事件色大按钮，点击即记录该事件。
 */
class WidgetSingleConfigureActivity : BaseActivity() {

    private lateinit var binding: ActivityWidgetConfigureBinding
    private lateinit var repo: EventRepository
    private var widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)   // BaseActivity：动态取色 + 沉浸式，各页统一
        binding = ActivityWidgetConfigureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 与其他页面共用同一套沉浸逻辑（本页没有 AppBar，顶部内边距落在 toolbar 上）
        setupChrome(
            binding.toolbar, null, binding.root,
            R.string.widget_single_bind_title, showBack = true,
            scrollContent = binding.recyclerBind
        )
        repo = EventRepository(this)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID

        binding.tvHint.text = getString(R.string.widget_single_bind_hint)

        val events = repo.getEvents()
        binding.recyclerBind.layoutManager = LinearLayoutManager(this)
        binding.recyclerBind.adapter = Adapter(events)

        if (events.isEmpty()) {
            binding.tvHint.text = getString(R.string.widget_single_no_event)
        }
    }

    private fun onPick(eventId: Long) {
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        WidgetPrefs.saveSingleBind(this, widgetId, eventId)
        val manager = AppWidgetManager.getInstance(this)
        manager.updateAppWidget(widgetId, WidgetSingleProvider.buildRemoteViews(this, widgetId))
        // ⚠️ 必须把 EXTRA_APPWIDGET_ID 放进结果 Intent：带 configure 的小组件，
        //    launcher 靠它确认「哪个 widgetId 被配置成功」。只 setResult(RESULT_OK)
        //    不带这个 extra，launcher 视为无效 → 选完事件页面关掉、桌面什么都不出现（添加失败）。
        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        setResult(RESULT_OK, resultValue)
        finish()
    }

    private inner class Adapter(private val events: List<TimestampEvent>) :
        RecyclerView.Adapter<Adapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemWidgetBindBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount(): Int = events.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ev = events[position]
            holder.b.tvName.text = ev.name
            holder.b.tvSub.text = getString(R.string.event_count, repo.recordCount(ev.id))
            holder.b.viewDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ev.color)
            }
            holder.b.root.setOnClickListener { onPick(ev.id) }
        }

        inner class VH(val b: ItemWidgetBindBinding) : RecyclerView.ViewHolder(b.root)
    }
}
