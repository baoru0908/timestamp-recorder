package com.timestamp.recorder

import android.content.Context

/** 小组件公共配置：圆角档位 + 单事件小组件绑定存储 */
object WidgetPrefs {
    const val PREFS = "tsr_widget"
    const val KEY_CORNER = "widget_corner"          // 圆角档位：8 / 16 / 24 / 32
    const val CORNER_DEFAULT = 16

    // 单事件小组件的绑定：single_<widgetId> -> eventId
    private fun singleKey(widgetId: Int) = "single_$widgetId"

    fun corner(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CORNER, CORNER_DEFAULT)

    fun setCorner(context: Context, corner: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_CORNER, corner).apply()
    }

    fun saveSingleBind(context: Context, widgetId: Int, eventId: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(singleKey(widgetId), eventId).apply()
    }

    fun singleBind(context: Context, widgetId: Int): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(singleKey(widgetId), -1L)

    fun removeSingleBind(context: Context, widgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(singleKey(widgetId)).apply()
    }

    // ── 自定义组件：custom_<widgetId> -> title / bgColor / 选中事件集合 ──
    private fun cTitle(id: Int) = "custom_title_$id"
    private fun cBg(id: Int) = "custom_bg_$id"
    private fun cImage(id: Int) = "custom_image_$id"
    private fun cEvents(id: Int) = "custom_events_$id"

    const val CUSTOM_BG_DEFAULT = 0xFF2D2D3A.toInt()

    fun customTitle(context: Context, widgetId: Int): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(cTitle(widgetId), "我的小组件") ?: "我的小组件"

    fun customBg(context: Context, widgetId: Int): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(cBg(widgetId), CUSTOM_BG_DEFAULT)

    fun customImage(context: Context, widgetId: Int): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(cImage(widgetId), "") ?: ""

    fun setCustomImage(context: Context, widgetId: Int, path: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(cImage(widgetId), path).apply()
    }

    fun customEvents(context: Context, widgetId: Int): Set<Long> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(cEvents(widgetId), emptySet())
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet() ?: emptySet()

    fun saveCustom(context: Context, widgetId: Int, title: String, bg: Int, events: Set<Long>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(
                cTitle(widgetId),
                title.ifBlank { context.getString(R.string.widget_default_title) }
            )
            .putInt(cBg(widgetId), bg)
            .putStringSet(cEvents(widgetId), events.map { it.toString() }.toSet())
            .apply()
    }

    fun removeCustom(context: Context, widgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(cTitle(widgetId)).remove(cBg(widgetId)).remove(cEvents(widgetId)).apply()
    }

    /** 圆角档位对应的按钮背景资源 */
    fun cornerRes(corner: Int): Int = when (corner) {
        8 -> R.drawable.bg_corner_r8
        24 -> R.drawable.bg_corner_r24
        32 -> R.drawable.bg_corner_r32
        else -> R.drawable.bg_corner_r16
    }

    /** 圆角档位对应的小组件容器背景资源 */
    fun widgetBgRes(corner: Int): Int = when (corner) {
        8 -> R.drawable.widget_bg_r8
        24 -> R.drawable.widget_bg_r24
        32 -> R.drawable.widget_bg_r32
        else -> R.drawable.widget_bg_r16
    }
}
