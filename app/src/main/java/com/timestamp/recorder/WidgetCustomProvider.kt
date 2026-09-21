package com.timestamp.recorder

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.widget.RemoteViews

/**
 * 小组件类型三：自定义。
 * 由配置页指定：标题、背景色、要显示哪些事件。行渲染与「全部事件」一致（圆点 + 名 + 最近时间）。
 * 背景用现画的圆角矩形 Bitmap，避免共用 shape 的 tint 跨实例串色。
 */
class WidgetCustomProvider : AppWidgetProvider() {

    companion object {
        private const val MAX_ROWS = 6
        private val ROW_IDS = intArrayOf(R.id.row0, R.id.row1, R.id.row2, R.id.row3, R.id.row4, R.id.row5)
        private val DOT_IDS = intArrayOf(R.id.dot0, R.id.dot1, R.id.dot2, R.id.dot3, R.id.dot4, R.id.dot5)
        private val NAME_IDS = intArrayOf(R.id.name0, R.id.name1, R.id.name2, R.id.name3, R.id.name4, R.id.name5)
        private val TIME_IDS = intArrayOf(R.id.time0, R.id.time1, R.id.time2, R.id.time3, R.id.time4, R.id.time5)

        fun buildRemoteViews(context: Context, appWidgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_custom)

            val title = WidgetPrefs.customTitle(context, appWidgetId)
            val bg = WidgetPrefs.customBg(context, appWidgetId)
            val imagePath = WidgetPrefs.customImage(context, appWidgetId)
            val selected = WidgetPrefs.customEvents(context, appWidgetId)

            // 背景：优先用户图片，否则按自定义色现画圆角矩形
            val bmp = if (imagePath.isNotBlank()) loadScaledBitmap(context, imagePath) else null
            if (bmp != null) views.setImageViewBitmap(R.id.bgCustom, bmp)
            else views.setImageViewBitmap(R.id.bgCustom, roundedRectBitmap(context, bg, WidgetPrefs.corner(context)))

            // 前景色：背景是图片时保持白字（图片内容不可预测，白字 + 图片是通用组合）；
            // 背景是纯色时按 onColor 规则（默认白、过亮转近黑）。
            val onBg = if (bmp != null) EventColors.WHITE else EventColors.onColor(bg)
            val onBgDim = withAlpha(onBg, 0xB3)

            views.setTextViewText(R.id.tvCustomTitle, title)
            views.setTextColor(R.id.tvCustomTitle, onBg)
            views.setTextColor(R.id.widgetEmpty, onBgDim)

            val repo = EventRepository(context)
            val events = if (selected.isEmpty()) emptyList()
            else repo.getEvents().filter { it.id in selected }.take(MAX_ROWS)

            for (i in 0 until MAX_ROWS) {
                val ev = events.getOrNull(i)
                if (ev == null) {
                    views.setViewVisibility(ROW_IDS[i], android.view.View.GONE)
                    continue
                }
                views.setViewVisibility(ROW_IDS[i], android.view.View.VISIBLE)
                views.setTextViewText(NAME_IDS[i], ev.name)
                val timeText = if (ev.isInterval) {
                    val ongoing = repo.ongoingInterval(ev.id)
                    val ivs = repo.getIntervals(ev.id)
                    when {
                        ongoing != null -> context.getString(R.string.widget_recording_dot)
                        ivs.isNotEmpty() -> TimeFormat.hm(ivs.first().end ?: ivs.first().start)
                        else -> context.getString(R.string.widget_interval_idle)
                    }
                } else {
                    val last = repo.lastRecord(ev.id)
                    if (last != null) TimeFormat.hm(last) else context.getString(R.string.widget_row_no_record)
                }
                views.setTextViewText(TIME_IDS[i], timeText)
                views.setTextColor(NAME_IDS[i], onBg)
                views.setTextColor(TIME_IDS[i], onBgDim)
                views.setTextViewText(DOT_IDS[i], "●")
                views.setInt(DOT_IDS[i], "setTextColor", ev.color)

                val pi = PendingIntent.getBroadcast(
                    context,
                    appWidgetId * 1000 + i,
                    Intent(context, TimestampWidgetProvider::class.java).apply {
                        action = TimestampWidgetProvider.ACTION_RECORD
                        data = WidgetRecordHelper.recordUri(appWidgetId, ev.id)
                        putExtra(TimestampWidgetProvider.EXTRA_EVENT_ID, ev.id)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(ROW_IDS[i], pi)
            }

            views.setViewVisibility(R.id.widgetEmpty, if (events.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE)
            views.setViewVisibility(R.id.tvMore, android.view.View.GONE)

            val openPi = PendingIntent.getActivity(
                context, appWidgetId, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.tvCustomTitle, openPi)
            return views
        }

        private fun dotBitmap(context: Context, color: Int): Bitmap {
            val d = context.resources.displayMetrics.density
            val px = (12 * d * 2).toInt().coerceAtLeast(24)
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawCircle(px / 2f, px / 2f, px / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            return bmp
        }

        /** 解码用户背景图，降采样 + 按全局圆角裁剪四角 */
        private fun loadScaledBitmap(context: Context, path: String): Bitmap? {
            return try {
                val target = 720
                val opts = android.graphics.BitmapFactory.Options()
                opts.inJustDecodeBounds = true
                android.graphics.BitmapFactory.decodeFile(path, opts)
                var sample = 1
                val larger = maxOf(opts.outWidth, opts.outHeight)
                while (larger / sample > target) sample *= 2
                opts.inJustDecodeBounds = false
                opts.inSampleSize = sample
                val src = android.graphics.BitmapFactory.decodeFile(path, opts) ?: return null
                val r = WidgetPrefs.corner(context) * context.resources.displayMetrics.density
                val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(out)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                paint.shader = android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                canvas.drawRoundRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), r, r, paint)
                out
            } catch (e: Exception) { null }
        }

        /** 按色画一张圆角矩形背景 Bitmap（无圆角资源依赖，跨实例不共享 ConstantState） */
        private fun roundedRectBitmap(context: Context, color: Int, cornerDp: Int = 16): Bitmap {
            val d = context.resources.displayMetrics.density
            val w = (160 * d).toInt().coerceAtLeast(320)
            val h = (160 * d).toInt().coerceAtLeast(320)
            val r = cornerDp * d
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, paint)
            return bmp
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { manager.updateAppWidget(it, buildRemoteViews(context, it)) }
    }
}
