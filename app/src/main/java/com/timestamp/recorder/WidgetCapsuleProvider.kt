package com.timestamp.recorder

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * 小组件类型三：单事件 2×1 胶囊。
 * 横向胶囊（事件色填充），左侧事件名 + 右侧「记录」按钮，点击即记录。
 * 复用单事件配置页（WidgetSingleConfigureActivity）绑定事件。
 */
class WidgetCapsuleProvider : AppWidgetProvider() {

    companion object {
        fun buildRemoteViews(context: Context, appWidgetId: Int): RemoteViews {
            val repo = EventRepository(context)
            val eventId = WidgetPrefs.singleBind(context, appWidgetId)
            val event = if (eventId > 0) repo.getEvent(eventId) else null

            val views = RemoteViews(context.packageName, R.layout.widget_capsule)
            val color = event?.color ?: 0xFF546E7A.toInt()
            // 背景改用「ImageView 圆角 shape + setColorFilter」：
            // 规避多实例共享 ConstantState 导致的串色（BUG-1）与 setColorStateList 的 API 31 门槛（BUG-2）
            views.setImageViewResource(R.id.bgImage, R.drawable.bg_capsule)
            views.setInt(R.id.bgImage, "setColorFilter", color)

            if (event != null) {
                views.setTextViewText(R.id.tvName, event.name)
                views.setTextViewText(R.id.tvAction, context.getString(R.string.widget_capsule_record))
            } else {
                // 胶囊只有一行高度，套用「事件已删除\n长按重新配置」会被截断，这里用短文案
                views.setTextViewText(R.id.tvName, context.getString(R.string.widget_capsule_missing))
                views.setTextViewText(R.id.tvAction, "")
            }

            // requestCode 掺入实例 id + data 带上「实例 / 事件」，保证每个实例的 PI 唯一（BUG-3）
            val clickIntent = Intent(context, TimestampWidgetProvider::class.java).apply {
                action = TimestampWidgetProvider.ACTION_RECORD
                data = WidgetRecordHelper.recordUri(appWidgetId, eventId)
                putExtra(TimestampWidgetProvider.EXTRA_EVENT_ID, eventId)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.rowRoot, pi)
            return views
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { manager.updateAppWidget(it, buildRemoteViews(context, it)) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetPrefs.removeSingleBind(context, it) }
    }
}
