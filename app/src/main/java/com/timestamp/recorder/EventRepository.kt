package com.timestamp.recorder

import android.graphics.Color

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 事件模型：一个事件 = 一个可独立记录时间戳的分类 */
data class TimestampEvent(
    val id: Long,
    val name: String,
    val color: Int,
    val createdAt: Long,
    /** 记录形态：[TYPE_POINT] 点时刻（点一下记一个瞬间，默认）/ [TYPE_INTERVAL] 时间段（点开始、再点结束） */
    val type: Int = TYPE_POINT
) {
    val isInterval: Boolean get() = type == TYPE_INTERVAL

    companion object {
        /** 点事件：一次点击 = 一个毫秒时间戳 */
        const val TYPE_POINT = 0
        /** 区间事件：点击开始计时、再点击结束，存 [TimeInterval] */
        const val TYPE_INTERVAL = 1
    }
}

/**
 * 一条时间段记录：开始时刻 + 结束时刻。
 * [end] 为 null 表示「进行中」——结束时间未到，已用时在读取时按当前时刻现算。
 * 不挂任何后台服务/通知，纯靠两个时间戳差计算。
 */
data class TimeInterval(
    val start: Long,
    val end: Long?
) {
    val isOngoing: Boolean get() = end == null
    /** 时长（毫秒）；进行中按当前时刻算 */
    fun duration(now: Long = System.currentTimeMillis()): Long = (end ?: now) - start
}

/**
 * 分类染色色板（Material 风格 24 色）
 *
 * 🔒 **色板数值永久冻结（2026-09-21 主人明确要求）**：
 * 这 24 个 ARGB 值会被**持久化到用户数据里**（SharedPreferences 的事件 color 字段、
 * 以及 format 2 备份 JSON 的 events[].color），是已发布版本的行为契约。
 * 任何"重新配色 / 压深 / 换色相"的改动都会**静默改变老用户所有事件的观感**，
 * 并且会让「恢复旧备份」后的显示与当初不一致 —— 所以**不要改这里的任何数值**，
 * 也不要调整顺序（`colors.xml` 的 ev_* 与 strings.xml 的 event_color_names 按同一顺序对齐）。
 *
 * 需要解决"浅色事件上白字看不清"时，走 [onColor] 换**前景色**，而不是动色板。
 */
object EventColors {
    val palette = listOf(
        // 第一组（原 12 色）
        0xFFE53935.toInt(), // 红
        0xFFFB8C00.toInt(), // 橙
        0xFFF9A825.toInt(), // 琥珀
        0xFF43A047.toInt(), // 绿
        0xFF00897B.toInt(), // 青绿
        0xFF00ACC1.toInt(), // 青
        0xFF1E88E5.toInt(), // 蓝
        0xFF3949AB.toInt(), // 靛
        0xFF8E24AA.toInt(), // 紫
        0xFFD81B60.toInt(), // 粉
        0xFF6D4C41.toInt(), // 棕
        0xFF546E7A.toInt(), // 蓝灰
        // 第二组（扩充）
        0xFFC62828.toInt(), // 深红
        0xFFEF6C00.toInt(), // 深橙
        0xFFFDD835.toInt(), // 明黄
        0xFF689F38.toInt(), // 草绿
        0xFF26A69A.toInt(), // 薄荷
        0xFF00BCD4.toInt(), // 亮青
        0xFF42A5F5.toInt(), // 浅蓝
        0xFF5C6BC0.toInt(), // 浅靛
        0xFFAB47BC.toInt(), // 浅紫
        0xFFEC407A.toInt(), // 玫粉
        0xFF8D6E63.toInt()  // 浅棕
        // 2026-09-21：移除「浅蓝灰 #78909C」——它与「蓝灰 #546E7A」区分度最低、观感最浑，
        // 去掉后 23 色 + 末尾/开头一格「自定义」正好凑成 4×6 = 24 格的整行网格。
        // ⚠️ 只影响"以后能选哪些色"：已存在事件的 color 是存进数据的整数，一个都没动。
    )
    fun random(): Int = palette.random()

    /**
     * 事件色当作**背景**时的可读前景色。
     *
     * 规则（2026-09-21 主人拍板的两条要求合起来）：
     * - **默认白色** —— 全 App 统一白字最一致（曾试过"按对比度逐个选白/黑"，
     *   同一列里黑白混排不统一，被否掉）；
     * - **底色过亮时才转近黑** —— 纯白 #FFFFFF（白字 1:1）、明黄 #FDD835（1.40:1）、
     *   琥珀 #F9A825（1.97:1）这类底色上白字等于消失，属于"白色底的文字可读性"问题，
     *   必须兜住。
     *
     * 判定用**相对亮度 > 0.45**（而不是逐色比对比度）：这样只会翻掉"白/亮黄"这一档，
     * 色板 23 色里仅明黄、琥珀两种会转深色，白字方案在其余 21 色上全部保留。
     */
    /** 底色"过亮"的判定线（WCAG 相对亮度）：白/明黄/琥珀这一档 */
    const val LIGHT_BACKGROUND_LUMINANCE = 0.45

    fun onColor(background: Int): Int =
        if (relativeLuminance(background) > LIGHT_BACKGROUND_LUMINANCE) NEAR_BLACK else WHITE

    /** 白字达标？ */
    fun whitePasses(background: Int): Boolean = contrastWithWhite(background) >= 4.5

    /** 白色正文的 WCAG AA 门槛 */
    const val WHITE_TEXT_MIN_CONTRAST = 4.5

    /**
     * 给定色相/饱和度，求「还能让白字达标」的最大明度值。
     *
     * 用于自定义取色器：它把 SV 面板的**纵向范围**按这个上限压下来，
     * 于是面板里每一个点都是"白字读得清"的颜色 —— 从根上避免挑出一个
     * 白色/极浅色事件，导致卡片上的白点、白色药丸、白色文字全部糊在一起
     * （2026-09-21 真机实测：#FFFFFF 事件在深色卡片上就是一片白）。
     *
     * 相对亮度对 v 单调递增，所以用二分（10 次足够，误差 <0.1%）。
     */
    fun maxValueForWhite(hue: Float, sat: Float): Float {
        var lo = 0.05f
        var hi = 1f
        if (contrastWithWhite(Color.HSVToColor(floatArrayOf(hue, sat, hi))) >= WHITE_TEXT_MIN_CONTRAST) {
            return hi
        }
        repeat(12) {
            val mid = (lo + hi) / 2f
            if (contrastWithWhite(Color.HSVToColor(floatArrayOf(hue, sat, mid))) >= WHITE_TEXT_MIN_CONTRAST) {
                lo = mid
            } else {
                hi = mid
            }
        }
        return lo
    }

    /**
     * 把一个任意颜色夹到「白字可读」的范围（保持色相/饱和度，只压明度）。
     * 打开取色器时对初始色先过一次，于是像 #FFFFFF 这种历史颜色也能被纠正回可用状态。
     */
    fun forWhiteText(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val maxV = maxValueForWhite(hsv[0], hsv[1])
        if (hsv[2] <= maxV) return color
        hsv[2] = maxV
        return Color.HSVToColor(hsv)
    }

    val WHITE = 0xFFFFFFFF.toInt()
    /** 不用纯黑：与 Material 的 onSurface(#1A1C1E) 同族，避免在彩色底上显得生硬 */
    val NEAR_BLACK = 0xFF1A1C1E.toInt()

    /** sRGB 单通道线性化（WCAG 2.1 定义的分段函数） */
    private fun channelLuminance(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    /** 相对亮度：0.2126R + 0.7152G + 0.0722B */
    private fun relativeLuminance(color: Int): Double =
        0.2126 * channelLuminance((color shr 16) and 0xFF) +
                0.7152 * channelLuminance((color shr 8) and 0xFF) +
                0.0722 * channelLuminance(color and 0xFF)

    private fun contrastRatio(a: Double, b: Double): Double =
        (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)

    private fun contrastWithWhite(color: Int): Double =
        contrastRatio(relativeLuminance(color), 1.0)

    private fun contrastWithBlack(color: Int): Double =
        contrastRatio(relativeLuminance(color), 0.0)
}

/** 时间线条目：一条记录 + 所属事件信息（事件名 / 事件色在记录时快照，避免查询时反复取事件表） */
data class TimelineRecord(
    val eventId: Long,
    val eventName: String,
    val eventColor: Int,
    val millis: Long
)

/** 时间线条目：一条时间段 + 所属事件信息。按 start 参与时间线排序。 */
data class TimelineInterval(
    val eventId: Long,
    val eventName: String,
    val eventColor: Int,
    val start: Long,
    val end: Long?,
    val durationMillis: Long
)

/** 数据层：SharedPreferences + JSON，零第三方依赖、纯本地存储 */
class EventRepository(context: Context) {

    private val prefs = context.getSharedPreferences("tsr_data", Context.MODE_PRIVATE)
    private val eventsKey = "events"
    private fun recordsKey(id: Long) = "records_$id"
    private fun intervalsKey(id: Long) = "intervals_$id"

    /**
     * 数据变更通知：**任何**写入（详情页、主页快捷记录、桌面小组件、恢复备份…）
     * 都会回调，供前台页面统一刷新 —— 避免「删了却还显示」这类刷新时序缺口。
     */
    fun registerChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(l)
    }

    fun unregisterChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(l)
    }

    // ---------- 事件 CRUD ----------

    @Synchronized
    fun getEvents(): List<TimestampEvent> {
        val raw = prefs.getString(eventsKey, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TimestampEvent(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    color = o.getInt("color"),
                    createdAt = o.getLong("createdAt"),
                    // 老数据没有 type 字段 → 默认点事件，向后兼容
                    type = o.optInt("type", TimestampEvent.TYPE_POINT)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun getEvent(id: Long): TimestampEvent? = getEvents().find { it.id == id }

    @Synchronized
    fun addEvent(name: String, color: Int, type: Int = TimestampEvent.TYPE_POINT): TimestampEvent {
        val events = getEvents().toMutableList()
        var id = System.currentTimeMillis()
        while (events.any { it.id == id }) id++
        val ev = TimestampEvent(id, name.trim(), color, System.currentTimeMillis(), type)
        events.add(ev)
        saveEvents(events)
        return ev
    }

    @Synchronized
    fun updateEvent(id: Long, name: String, color: Int, type: Int) {
        saveEvents(getEvents().map {
            if (it.id == id) it.copy(name = name.trim(), color = color, type = type) else it
        })
    }

    @Synchronized
    fun deleteEvent(id: Long) {
        saveEvents(getEvents().filterNot { it.id == id })
        prefs.edit().remove(recordsKey(id)).remove(intervalsKey(id)).apply()
    }

    /**
     * 按「手动顺序」（即存储数组的顺序）返回事件列表。
     * 拖拽排序时直接调整存储顺序，无需额外的 order 字段。
     */
    fun getEventsManualOrder(): List<TimestampEvent> = getEvents()

    /** 按「最近记录时间」降序返回；无记录的事件排在最后。 */
    fun getEventsByRecent(): List<TimestampEvent> {
        val lastMap = getEvents().associateWith { lastRecord(it.id) ?: Long.MIN_VALUE }
        return getEvents().sortedByDescending { lastMap[it] }
    }

    /** 拖拽结束时持久化新的手动顺序（传入事件 id 的顺序即新顺序）。 */
    @Synchronized
    fun setEventsOrder(orderedIds: List<Long>) {
        val map = getEvents().associateBy { it.id }
        val reordered = orderedIds.mapNotNull { map[it] }
        val missing = map.values.filter { it.id !in orderedIds }
        saveEvents(reordered + missing)
    }

    /** 用备份数据整体替换（先清空旧的事件与记录，再写入备份内容）。 */
    @Synchronized
    fun replaceAllData(data: BackupHelper.BackupData) {
        // 清空所有记录键，避免残留旧数据
        prefs.all.keys.filter { it.startsWith("records_") || it.startsWith("intervals_") }.forEach {
            prefs.edit().remove(it).apply()
        }
        saveEvents(data.events)
        data.records.forEach { (id, list) -> saveRecords(id, list) }
        data.intervals.forEach { (id, list) -> saveIntervals(id, list) }
    }

    private fun saveEvents(events: List<TimestampEvent>) {
        val arr = JSONArray()
        events.forEach {
            arr.put(JSONObject()
                .put("id", it.id)
                .put("name", it.name)
                .put("color", it.color)
                .put("createdAt", it.createdAt)
                .put("type", it.type))
        }
        prefs.edit().putString(eventsKey, arr.toString()).apply()
    }

    // ---------- 记录（时间戳，毫秒精度，最新在前） ----------

    @Synchronized
    fun getRecords(eventId: Long): List<Long> {
        val raw = prefs.getString(recordsKey(eventId), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getLong(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun addRecord(eventId: Long): Long {
        val millis = System.currentTimeMillis()
        val list = getRecords(eventId).toMutableList()
        list.add(0, millis)
        saveRecords(eventId, list)
        return millis
    }

    /**
     * 按**时间戳值**删除一条点记录。
     * ⚠️ 刻意不按下标删：适配器下标可能与存储错位（外部插/删后未重绑），
     * 错位会删错条、越界会静默不删 —— 只认记录本身的值才稳定。
     */
    @Synchronized
    fun deleteRecord(eventId: Long, millis: Long) {
        val list = getRecords(eventId).toMutableList()
        if (list.remove(millis)) saveRecords(eventId, list)
    }

    /** 批量删除：按记录的时间戳值删除（批量勾选用） */
    @Synchronized
    fun deleteRecords(eventId: Long, toRemove: Set<Long>) {
        if (toRemove.isEmpty()) return
        val list = getRecords(eventId).toMutableList()
        list.removeAll(toRemove)
        saveRecords(eventId, list)
    }

    @Synchronized
    fun undoLast(eventId: Long): Boolean {
        val list = getRecords(eventId).toMutableList()
        if (list.isEmpty()) return false
        list.removeAt(0)
        saveRecords(eventId, list)
        return true
    }

    @Synchronized
    fun clearRecords(eventId: Long) {
        prefs.edit().remove(recordsKey(eventId)).apply()
    }

    fun recordCount(eventId: Long): Int = getRecords(eventId).size

    fun lastRecord(eventId: Long): Long? = getRecords(eventId).firstOrNull()

    /** 时间线：全部事件的所有记录合并，按时间倒序（最新在前）。事件被删时其记录一并删除，故无需过滤。 */
    @Synchronized
    fun getAllRecords(): List<TimelineRecord> {
        val out = mutableListOf<TimelineRecord>()
        for (ev in getEvents()) {
            for (m in getRecords(ev.id)) {
                out.add(TimelineRecord(ev.id, ev.name, ev.color, m))
            }
        }
        return out.sortedByDescending { it.millis }
    }

    private fun saveRecords(eventId: Long, records: List<Long>) {
        val arr = JSONArray()
        records.forEach { arr.put(it) }
        prefs.edit().putString(recordsKey(eventId), arr.toString()).apply()
    }

    // ---------- 时间段（区间事件；最新在前，进行中的那条排最前） ----------

    /** 读取某事件的全部时间段（最新在前）。进行中的区间 end 缺省。 */
    @Synchronized
    fun getIntervals(eventId: Long): List<TimeInterval> {
        val raw = prefs.getString(intervalsKey(eventId), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TimeInterval(
                    start = o.getLong("start"),
                    end = if (o.has("end")) o.getLong("end") else null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 当前进行中的区间（至多一条）；没有则 null。 */
    @Synchronized
    fun ongoingInterval(eventId: Long): TimeInterval? =
        getIntervals(eventId).firstOrNull { it.isOngoing }

    /**
     * 开始一段：若已有进行中区间则先结束它（不会丢），再写入新的开始。
     * 返回新的开始时刻。
     */
    @Synchronized
    fun startInterval(eventId: Long): Long {
        val list = getIntervals(eventId).toMutableList()
        // 已有进行中的，先把它按「现在」结束，避免两条进行中
        val now = System.currentTimeMillis()
        for (i in list.indices) {
            if (list[i].isOngoing) list[i] = list[i].copy(end = now)
        }
        list.add(0, TimeInterval(now, null))
        saveIntervals(eventId, list)
        return now
    }

    /** 结束进行中的区间，返回更新后的区间；没有进行中的返回 null。 */
    @Synchronized
    fun stopInterval(eventId: Long): TimeInterval? {
        val list = getIntervals(eventId).toMutableList()
        val idx = list.indexOfFirst { it.isOngoing }
        if (idx < 0) return null
        val ended = list[idx].copy(end = System.currentTimeMillis())
        list[idx] = ended
        // 结束后把它按开始时间排到最前（最新）
        saveIntervals(eventId, list.sortedByDescending { it.start })
        return ended
    }

    /** 撤销最近一条：删最新一条已结束区间；若有进行中的，撤销=丢弃它（停止不保存）。 */
    @Synchronized
    fun undoLastInterval(eventId: Long): Boolean {
        val list = getIntervals(eventId).toMutableList()
        if (list.isEmpty()) return false
        // 进行中的在最前，撤销即丢弃
        if (list.first().isOngoing) {
            list.removeAt(0)
        } else {
            list.removeAt(0)
        }
        saveIntervals(eventId, list)
        return true
    }

    /** 按**开始时刻**删除一条时间段（同上：只认标识，不认下标）。 */
    @Synchronized
    fun deleteIntervalByStart(eventId: Long, start: Long) {
        val list = getIntervals(eventId).toMutableList()
        val idx = list.indexOfFirst { it.start == start }
        if (idx >= 0) {
            list.removeAt(idx)
            saveIntervals(eventId, list)
        }
    }

    @Synchronized
    fun clearIntervals(eventId: Long) {
        prefs.edit().remove(intervalsKey(eventId)).apply()
    }

    fun intervalCount(eventId: Long): Int = getIntervals(eventId).size

    /** 时间线用：合并所有事件的区间（取开始时刻为排序键）。 */
    @Synchronized
    fun getAllIntervals(): List<TimelineInterval> {
        val out = mutableListOf<TimelineInterval>()
        for (ev in getEvents()) {
            if (!ev.isInterval) continue
            for (iv in getIntervals(ev.id)) {
                out.add(TimelineInterval(ev.id, ev.name, ev.color, iv.start, iv.end, iv.duration()))
            }
        }
        return out.sortedByDescending { it.start }
    }

    private fun saveIntervals(eventId: Long, intervals: List<TimeInterval>) {
        val arr = JSONArray()
        intervals.forEach {
            val o = JSONObject().put("start", it.start)
            if (it.end != null) o.put("end", it.end)
            arr.put(o)
        }
        prefs.edit().putString(intervalsKey(eventId), arr.toString()).apply()
    }
}

/** 时间格式化工具 */
object TimeFormat {
    private val full = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
    private val hm = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    private val short = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    fun full(millis: Long): String = full.format(java.util.Date(millis))
    fun hm(millis: Long): String = hm.format(java.util.Date(millis))
    fun short(millis: Long): String = short.format(java.util.Date(millis))

    /**
     * 时长格式化：进行中用「约 N」，已结束用精确。
     * 例：1h30m → "1小时30分"；45m → "45分钟"；90s → "1分钟"；2d3h → "2天3小时"
     */
    fun duration(millis: Long): String {
        if (millis < 0) return "0分钟"
        val totalMin = millis / 60_000
        val days = totalMin / 1440
        val hours = (totalMin % 1440) / 60
        val mins = totalMin % 60
        return when {
            days > 0 && hours > 0 -> "${days}天${hours}小时"
            days > 0 -> "${days}天"
            hours > 0 && mins > 0 -> "${hours}小时${mins}分"
            hours > 0 -> "${hours}小时"
            mins > 0 -> "${mins}分钟"
            else -> "不到1分钟"
        }
    }

    /**
     * 秒表样式（进行中读秒）：不足 1 小时 → `MM:SS`，超过 1 小时 → `H:MM:SS`。
     * 用等宽数字呈现，逐秒跳动时数字不抖动。
     */
    fun durationClock(millis: Long): String {
        val total = (if (millis < 0) 0 else millis) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        else String.format(java.util.Locale.getDefault(), "%02d:%02d", m, s)
    }

    /**
     * 相对时间（与参考 App「Last Time」时间线一致的中文格式）：
     * 刚刚 / N 分钟前 / N 小时, M 分钟前 / N 天前
     */
    fun relative(context: android.content.Context, millis: Long, now: Long = System.currentTimeMillis()): String {
        val diff = now - millis
        if (diff < 60_000) return context.getString(R.string.timeline_just_now)
        val min = diff / 60_000
        return when {
            min < 60 -> context.getString(R.string.timeline_minutes_ago, min)
            min < 1440 -> {
                val h = min / 60
                val m = min % 60
                if (m == 0L) context.getString(R.string.timeline_hours_ago, h)
                else context.getString(R.string.timeline_hours_minutes_ago, h, m)
            }
            else -> context.getString(R.string.timeline_days_ago, min / 1440)
        }
    }
}
