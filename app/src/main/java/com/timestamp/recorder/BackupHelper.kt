package com.timestamp.recorder

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON 全量备份 / 恢复（零权限、走系统 SAF）。
 *
 * 备份结构（format 2）：
 * {
 *   "app": "timestamp-recorder",
 *   "format": 2,
 *   "exportedAt": <millis>,
 *   "events": [ {id,name,color,createdAt,type}, ... ],
 *   "records": { "<eventId>": [<millis>, ...], ... },
 *   "intervals": { "<eventId>": [ {start, end?}, ... ], ... }
 * }
 *
 * format 1（仅 records，无 type/interval）仍能解析：type 缺省点事件、intervals 为空。
 */
object BackupHelper {
    private const val MAGIC = "timestamp-recorder"
    private const val FORMAT = 2

    data class BackupData(
        val events: List<TimestampEvent>,
        val records: Map<Long, List<Long>>,
        val intervals: Map<Long, List<TimeInterval>> = emptyMap()
    )

    fun exportJson(repo: EventRepository): String {
        val root = JSONObject()
        root.put("app", MAGIC)
        root.put("format", FORMAT)
        root.put("exportedAt", System.currentTimeMillis())

        val evArr = JSONArray()
        repo.getEvents().forEach { e ->
            evArr.put(JSONObject().apply {
                put("id", e.id)
                put("name", e.name)
                put("color", e.color)
                put("createdAt", e.createdAt)
                put("type", e.type)
            })
        }
        root.put("events", evArr)

        val recObj = JSONObject()
        repo.getEvents().forEach { e ->
            val arr = JSONArray()
            repo.getRecords(e.id).forEach { arr.put(it) }
            recObj.put(e.id.toString(), arr)
        }
        root.put("records", recObj)

        val ivObj = JSONObject()
        repo.getEvents().forEach { e ->
            if (!e.isInterval) return@forEach
            val arr = JSONArray()
            repo.getIntervals(e.id).forEach { iv ->
                val o = JSONObject().put("start", iv.start)
                if (iv.end != null) o.put("end", iv.end)
                arr.put(o)
            }
            ivObj.put(e.id.toString(), arr)
        }
        root.put("intervals", ivObj)

        return root.toString(2)
    }

    /** 解析备份 JSON；格式不符返回 null。format 1/2 均可。 */
    fun parse(json: String): BackupData? {
        return try {
            val root = JSONObject(json)
            if (root.optString("app") != MAGIC) return null
            val evArr = root.getJSONArray("events")
            val events = (0 until evArr.length()).map { i ->
                val o = evArr.getJSONObject(i)
                TimestampEvent(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    color = o.getInt("color"),
                    createdAt = o.getLong("createdAt"),
                    type = o.optInt("type", TimestampEvent.TYPE_POINT)
                )
            }
            val recObj = root.getJSONObject("records")
            val records = mutableMapOf<Long, List<Long>>()
            recObj.keys().forEach { k ->
                val arr = recObj.getJSONArray(k)
                records[k.toLong()] = (0 until arr.length()).map { arr.getLong(it) }
            }
            // intervals：format 1 没有该字段 → 空
            val intervals = mutableMapOf<Long, List<TimeInterval>>()
            if (root.has("intervals")) {
                val ivObj = root.getJSONObject("intervals")
                ivObj.keys().forEach { k ->
                    val arr = ivObj.getJSONArray(k)
                    intervals[k.toLong()] = (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        TimeInterval(
                            start = o.getLong("start"),
                            end = if (o.has("end")) o.getLong("end") else null
                        )
                    }
                }
            }
            BackupData(events, records, intervals)
        } catch (_: Exception) {
            null
        }
    }
}
