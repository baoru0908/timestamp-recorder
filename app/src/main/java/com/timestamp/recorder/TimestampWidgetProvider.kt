package com.timestamp.recorder

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.view.View
import android.widget.RemoteViews

/**
 * 小组件类型一：全部事件。
 * 显示最多 6 个事件的「彩色圆点 + 名称 + 最近记录时间」，点击对应事件立即记录。
 *
 * 实现说明：这里**不使用** ListView + RemoteViewsService。
 * 列表型小组件要求桌面启动器跨进程 bindService 拉取数据，
 * 在部分 ROM（MIUI / HyperOS）上会直接失败并显示「载入窗口小部件时出现问题」。
 * 改为固定行（最多 6 行）渲染后不依赖任何跨进程服务，兼容性好得多。
 *
 * 配色：每行用中性半透明行底（bg_widget_row），事件色只出现在左侧圆点，
 * 通过 ImageView.setColorFilter 染色（跨 ROM 稳定），白字始终可读、颜色一眼可见。
 */
class TimestampWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_RECORD = "com.timestamp.recorder.ACTION_RECORD"
        const val EXTRA_EVENT_ID = "extra_event_id"

        /** 小组件最多渲染的事件行数 */
        private const val MAX_ROWS = 6

        private val ROW_IDS = intArrayOf(
            R.id.row0, R.id.row1, R.id.row2, R.id.row3, R.id.row4, R.id.row5
        )
        private val DOT_IDS = intArrayOf(
            R.id.dot0, R.id.dot1, R.id.dot2, R.id.dot3, R.id.dot4, R.id.dot5
        )
        private val NAME_IDS = intArrayOf(
            R.id.name0, R.id.name1, R.id.name2, R.id.name3, R.id.name4, R.id.name5
        )
        private val TIME_IDS = intArrayOf(
            R.id.time0, R.id.time1, R.id.time2, R.id.time3, R.id.time4, R.id.time5
        )

        fun buildRemoteViews(context: Context, appWidgetId: Int): RemoteViews {
            val corner = WidgetPrefs.corner(context)
            val views = RemoteViews(context.packageName, R.layout.widget_timestamp)
            views.setInt(R.id.widgetRoot, "setBackgroundResource", WidgetPrefs.widgetBgRes(corner))

            val repo = EventRepository(context)
            val events = repo.getEvents()

            for (i in 0 until MAX_ROWS) {
                val ev = events.getOrNull(i)
                if (ev == null) {
                    views.setViewVisibility(ROW_IDS[i], View.GONE)
                    continue
                }
                views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                views.setTextViewText(NAME_IDS[i], ev.name)
                val last = repo.lastRecord(ev.id)
                views.setTextViewText(
                    TIME_IDS[i],
                    if (last != null) TimeFormat.hm(last) else context.getString(R.string.widget_row_no_record)
                )

                // 彩色圆点：直接按事件色预渲染一张小圆点 Bitmap，再 setImageViewBitmap。
                // ⚠️ 不能用「共用 bg_dot shape + setColorFilter」：资源 drawable 默认共享同一实例，
                //    一行 setColorFilter 改的是同一份，后设的覆盖前面 → 所有圆点只剩最后一个事件的颜色（串色）。
                //    Bitmap 每色一张、彼此独立，跨 ROM 稳定（与单事件组件的结论一致）。
                views.setImageViewBitmap(DOT_IDS[i], coloredDot(context, ev.color))

                // PendingIntent 判等只看 requestCode + action/data/class/identity，**extras 不参与**；
                // 且 eventId 是 Long（毫秒时间戳），直接 toInt() 有截断碰撞窗口。故：
                //   requestCode 掺入实例 id + data 带上「实例 / 事件」→ 每个实例的 PI 唯一，
                //   不会出现「点 A 记录到 B」（BUG-3）。
                val pi = PendingIntent.getBroadcast(
                    context,
                    appWidgetId,
                    Intent(context, TimestampWidgetProvider::class.java).apply {
                        action = ACTION_RECORD
                        data = WidgetRecordHelper.recordUri(appWidgetId, ev.id)
                        putExtra(EXTRA_EVENT_ID, ev.id)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(ROW_IDS[i], pi)
            }

            // 空状态
            views.setViewVisibility(
                R.id.widgetEmpty, if (events.isEmpty()) View.VISIBLE else View.GONE
            )

            // 超出可显示行数时的提示
            val rest = events.size - MAX_ROWS
            if (rest > 0) {
                views.setViewVisibility(R.id.tvMore, View.VISIBLE)
                views.setTextViewText(R.id.tvMore, context.getString(R.string.widget_more, rest))
            } else {
                views.setViewVisibility(R.id.tvMore, View.GONE)
            }

            // 标题 / 空态指向 App 主页：按实例区分 requestCode，避免多实例共用同一 PI
            val openIntent = Intent(context, MainActivity::class.java)
            val openPi = PendingIntent.getActivity(
                context, appWidgetId, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetTitle, openPi)
            // 空状态（暂无事件）也可点击：文案写着「打开 App 添加」，点了必须真的能打开
            views.setOnClickPendingIntent(R.id.widgetEmpty, openPi)
            return views
        }

        /**
         * 按事件色渲染一张圆形圆点 Bitmap（带缓存）。
         * 圆点在布局里固定 10dp，这里按 2x 出图保证高分屏不糊。
         * 缓存在进程内按颜色复用，一次刷新最多 6 个事件，开销可忽略。
         */
        private val dotCache = java.util.concurrent.ConcurrentHashMap<Int, Bitmap>()

        private fun coloredDot(context: Context, color: Int): Bitmap =
            dotCache.getOrPut(color) {
                val d = context.resources.displayMetrics.density
                val px = (10 * d * 2).toInt().coerceAtLeast(20)
                val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(px / 2f, px / 2f, px / 2f, paint)
                bmp
            }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { manager.updateAppWidget(it, buildRemoteViews(context, it)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_RECORD) {
            WidgetRecordHelper.handleRecord(context, intent)
        }
    }
}

/** 小组件点击记录公共处理：记录 + 刷新两种类型全部实例 */
object WidgetRecordHelper {
    /**
     * 记录点击的 PendingIntent 唯一标识。
     * data 参与 PendingIntent 判等，用它承载「实例 id + 事件 id」，
     * 保证同一事件在不同小组件实例上的 PI 互不覆盖（见 BUG-3）。
     */
    fun recordUri(widgetId: Int, eventId: Long): Uri =
        Uri.parse("tsr://widget/$widgetId/record/$eventId")

    fun handleRecord(context: Context, intent: Intent) {
        val eventId = intent.getLongExtra(TimestampWidgetProvider.EXTRA_EVENT_ID, -1L)
        if (eventId > 0) {
            EventRepository(context).addRecord(eventId)
            refreshAll(context)
        }
    }

    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(ComponentName(context, TimestampWidgetProvider::class.java))
            .forEach { id -> manager.updateAppWidget(id, TimestampWidgetProvider.buildRemoteViews(context, id)) }
        manager.getAppWidgetIds(ComponentName(context, WidgetSingleProvider::class.java))
            .forEach { id -> manager.updateAppWidget(id, WidgetSingleProvider.buildRemoteViews(context, id)) }
        manager.getAppWidgetIds(ComponentName(context, WidgetCapsuleProvider::class.java))
            .forEach { id -> manager.updateAppWidget(id, WidgetCapsuleProvider.buildRemoteViews(context, id)) }
    }
}
