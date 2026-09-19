package com.timestamp.recorder

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

/**
 * 小组件类型二：单事件大按钮。
 * 创建时通过配置页绑定一个事件，桌面显示该事件色按钮，点击即记录。
 *
 * **尺寸自适应**：本组件可被拉伸为 1×1 ~ 4×4 之间的任意格子组合（含 1×2 / 3×1 等非方阵）。
 * 每次刷新都会读取该实例的真实格子尺寸，据此分三档渲染：
 *
 * | 档位 | 触发条件 | 布局 | 内容 |
 * |:---|:---|:---|:---|
 * | MINI | 宽高都不到 2 格 | `widget_single_mini` | 事件色方块 + 居中事件名 |
 * | BAR  | 高不到 2 格、宽 2 格以上 | `widget_single_bar` | 事件名 + 最近时间 + ＋ |
 * | CARD | 2×2 及以上 | `widget_single` | 事件名 / 时间 /（够大时）次数 / 提示 |
 *
 * 字号用 {@code setFloat(..., "setTextSize", ...)} 按尺寸实算，
 * 保证 1×1 不溢出、4×4 不空旷。尺寸变化由 [onAppWidgetOptionsChanged] 即时重绘。
 */
class WidgetSingleProvider : AppWidgetProvider() {

    /** 渲染档位 */
    private enum class Shape { MINI, BAR, CARD }

    companion object {
        /**
         * 「两格」的宽度阈值。Android 官方换算：n 格 ≈ 70n - 30 dp，两格即 110dp。
         * 某一边小于该值，说明该方向上只有 1 格。
         */
        private const val TWO_CELL_DP = 110

        /** 未绑定 / 绑定已删除时使用的中性灰蓝 */
        private const val MISSING_COLOR = 0xFF546E7A.toInt()

        private fun shapeOf(w: Int, h: Int): Shape = when {
            w < TWO_CELL_DP && h < TWO_CELL_DP -> Shape.MINI
            h < TWO_CELL_DP -> Shape.BAR
            else -> Shape.CARD
        }

        /**
         * 读取某方向上的格子尺寸（单位 dp）。
         * 刚添加到桌面、或 ROM 未回填 options 时拿到 0，此时按 2×2 兜底。
         */
        private fun sideDp(options: Bundle?, key: String): Int {
            val v = options?.getInt(key) ?: 0
            return if (v > 0) v else TWO_CELL_DP
        }

        fun buildRemoteViews(context: Context, appWidgetId: Int): RemoteViews {
            val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
            val w = sideDp(options, AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val h = sideDp(options, AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val shortSide = minOf(w, h)

            val repo = EventRepository(context)
            val eventId = WidgetPrefs.singleBind(context, appWidgetId)
            val event = if (eventId > 0) repo.getEvent(eventId) else null
            val last = event?.let { repo.lastRecord(it.id) }

            val shape = shapeOf(w, h)
            val views: RemoteViews = when (shape) {

                // ── 1×1：只放得下一个色块 + 事件名 ──
                Shape.MINI -> RemoteViews(context.packageName, R.layout.widget_single_mini).apply {
                    setTextViewText(
                        R.id.tvName,
                        event?.name ?: context.getString(R.string.widget_single_missing_short)
                    )
                    setFloat(R.id.tvName, "setTextSize", (shortSide / 4.2f).coerceIn(12f, 28f))
                }

                // ── 宽扁（2×1 / 3×1 / 4×1）：一行放完 ──
                // ⚠️ 宽度不足时隐藏「＋」图标（整块本就可点，图标只是提示）：
                //    实测窄 2×1 只有约 144dp，时间(≈62dp) + 图标(20dp) + 外边距(16dp) + 内边距(28dp)
                //    已占满，事件名只剩几个 dp —— 中文名连省略号都放不下，会整块空白（BUG-4）。
                Shape.BAR -> RemoteViews(context.packageName, R.layout.widget_single_bar).apply {
                    setTextViewText(
                        R.id.tvName,
                        event?.name ?: context.getString(R.string.widget_single_missing_short)
                    )
                    setTextViewText(R.id.tvInfo, if (last != null) TimeFormat.hm(last) else "")
                    setFloat(R.id.tvName, "setTextSize", (h / 2.6f).coerceIn(12f, 18f))
                    // 3×1 及以上（≥150dp）空间充裕，保留图标
                    setViewVisibility(R.id.ivIcon, if (w >= 150) View.VISIBLE else View.GONE)
                }

                // ── 2×2 及以上：完整卡片，次要信息随尺寸放出来 ──
                Shape.CARD -> RemoteViews(context.packageName, R.layout.widget_single).apply {
                    setTextViewText(
                        R.id.tvName,
                        event?.name ?: context.getString(R.string.widget_single_missing)
                    )
                    setTextViewText(R.id.tvInfo, if (last != null) TimeFormat.full(last) else "")
                    setFloat(R.id.tvName, "setTextSize", if (shortSide >= 180) 24f else 20f)

                    // 高度够才放「累计次数」与「点击记录」提示，2×2 时保持干净
                    val roomy = h >= 150
                    setViewVisibility(R.id.tvCount, if (roomy && event != null) View.VISIBLE else View.GONE)
                    setViewVisibility(R.id.tvHint, if (roomy) View.VISIBLE else View.GONE)
                    if (event != null) {
                        setTextViewText(
                            R.id.tvCount,
                            context.getString(R.string.widget_single_count, repo.recordCount(event.id))
                        )
                    }
                }
            }

            // 圆角背景：底层 ImageView 换圆角档位 + setColorFilter 染事件色。
            // 不用「容器 background + tint」的原因：
            //   ① 多实例共用同一 shape 资源时，tint 在部分 ROM launcher 上会写进共享 ConstantState，
            //      后刷新的实例覆盖前者 → 两个异色小组件互相串色（BUG-1）；
            //   ② RemoteViews.setColorStateList 是 API 31+，本工程 minSdk 24 会 NoSuchMethodError（BUG-2）。
            // ImageView.setColorFilter 内部会 mutate，跨 ROM 稳定，且 API 1 起可用。
            // 1×1 时把圆角压到 16dp 以内 —— 32dp 圆角会把 ~70dp 的小方块啃成一个圆点。
            val corner = if (shape == Shape.MINI) {
                minOf(WidgetPrefs.corner(context), 16)
            } else {
                WidgetPrefs.corner(context)
            }
            views.setImageViewResource(R.id.bgImage, WidgetPrefs.cornerRes(corner))
            views.setInt(R.id.bgImage, "setColorFilter", event?.color ?: MISSING_COLOR)

            // 整块可点：写入一条记录
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

    /** 用户拉伸 / 收缩小组件时立即按新尺寸重绘（1×1 ↔ 4×4 切换就靠这里）。 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        manager.updateAppWidget(appWidgetId, buildRemoteViews(context, appWidgetId))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetPrefs.removeSingleBind(context, it) }
    }
}
