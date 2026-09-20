package com.timestamp.recorder

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.timestamp.recorder.databinding.ActivityEventDetailBinding
import com.timestamp.recorder.databinding.DialogEditEventBinding
import com.timestamp.recorder.databinding.ItemRecordBinding
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale

/** 事件详情页：一键记录（点事件=记此刻 / 区间事件=开始·结束）+ 列表管理 + 导出 */
class EventDetailActivity : BaseActivity() {

    companion object {
        const val EXTRA_EVENT_ID = "extra_event_id"
    }

    private lateinit var binding: ActivityEventDetailBinding
    private lateinit var repo: EventRepository
    private var eventId: Long = -1L
    private val adapter = RowAdapter()

    /** 列表行：点记录与区间统一成一行 */
    private sealed class Row {
        data class Point(val millis: Long) : Row()
        data class Interval(val iv: TimeInterval) : Row()
    }

    /** 批量管理模式 */
    private var selectionMode = false
    private val selected = mutableSetOf<Row>()

    private val exportLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data ?: return@registerForActivityResult
                exportTo(uri)
            }
        }

    // 进行中区间的秒级跳动（仅区间模式且确有进行中时跑）
    private val ticker = Handler(Looper.getMainLooper())
    private var ticking = false
    private val tickRunnable = object : Runnable {
        override fun run() {
            val ev = currentEvent()
            val ongoing = ev?.takeIf { it.isInterval }?.let { repo.ongoingInterval(it.id) }
            if (ongoing != null) {
                refresh()
                ticker.postDelayed(this, 1000)
            } else {
                ticking = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEventDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupChrome(
            binding.toolbar, binding.appBar, binding.root, R.string.app_name,
            showBack = true, scrollContent = binding.recyclerRecords
        )
        installLiquidTopGlass(
            binding.topGlass, binding.appBar, binding.contentHost,
            contentBaseTop = resources.getDimensionPixelSize(R.dimen.space_4)
        )
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.selectionBar) { v, insets ->
            val nav = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            if (v.paddingBottom != nav) {
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, nav)
            }
            insets
        }
        repo = EventRepository(this)
        eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)

        binding.recyclerRecords.layoutManager = LinearLayoutManager(this)
        binding.recyclerRecords.adapter = adapter

        binding.btnRecord.setOnClickListener { onPrimaryAction() }
        binding.btnUndo.setOnClickListener { undo() }

        binding.btnSelectAll.setOnClickListener { toggleSelectAll() }
        binding.btnDeleteSelected.setOnClickListener { deleteSelected() }
        binding.btnCancelSelect.setOnClickListener { exitSelectionMode() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        startTickerIfNeeded()
    }

    override fun onPause() {
        stopTicker()
        super.onPause()
    }

    private fun currentEvent(): TimestampEvent? = repo.getEvent(eventId)

    private fun refresh() {
        val event = currentEvent()
        if (event == null) { finish(); return }
        binding.toolbar.title = event.name
        binding.btnRecord.iconTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())

        if (event.isInterval) {
            val ongoing = repo.ongoingInterval(eventId)
            val intervals = repo.getIntervals(eventId)
            // 主按钮：开始 / 结束；进行中保持事件色，文案表达状态
            binding.btnRecord.text = getString(if (ongoing != null) R.string.btn_stop else R.string.btn_start)
            binding.btnRecord.backgroundTintList = ColorStateList.valueOf(
                event.color
            )
            adapter.submit(intervals.map { Row.Interval(it) })
            binding.tvStats.text = if (ongoing != null) {
                getString(R.string.detail_ongoing, TimeFormat.duration(ongoing.duration()))
            } else if (intervals.isNotEmpty()) {
                getString(R.string.interval_last, TimeFormat.duration(intervals.first().duration()))
            } else {
                getString(R.string.event_intervals, 0)
            }
            binding.tvEmpty.visibility = if (intervals.isEmpty()) View.VISIBLE else View.GONE
        } else {
            val records = repo.getRecords(eventId)
            binding.btnRecord.text = getString(R.string.detail_record_btn)
            binding.btnRecord.backgroundTintList = ColorStateList.valueOf(event.color)
            adapter.submit(records.map { Row.Point(it) })
            val last = records.firstOrNull()
            binding.tvStats.text = getString(
                R.string.detail_stats,
                records.size,
                last?.let { TimeFormat.hm(it) } ?: getString(R.string.event_no_record)
            )
            binding.tvEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        }
        if (selectionMode) updateSelectionUI()
    }

    /** 主按钮：点事件=记此刻；区间=开始/结束切换 */
    private fun onPrimaryAction() {
        val event = currentEvent() ?: return
        if (event.isInterval) {
            val ongoing = repo.ongoingInterval(eventId)
            if (ongoing == null) {
                val start = repo.startInterval(eventId)
                Snackbar.make(binding.root, getString(R.string.toast_started, TimeFormat.hm(start)), Snackbar.LENGTH_SHORT).show()
                startTickerIfNeeded()
            } else {
                val ended = repo.stopInterval(eventId)
                stopTicker()
                Snackbar.make(binding.root,
                    getString(R.string.toast_stopped, event.name, TimeFormat.duration(ended?.duration() ?: 0)),
                    Snackbar.LENGTH_SHORT).show()
            }
        } else {
            val millis = repo.addRecord(eventId)
            Snackbar.make(binding.root, getString(R.string.toast_recorded, TimeFormat.full(millis)), Snackbar.LENGTH_SHORT).show()
        }
        refresh()
        WidgetRecordHelper.refreshAll(this)
    }

    private fun undo() {
        val event = currentEvent() ?: return
        val ok = if (event.isInterval) repo.undoLastInterval(eventId)
                 else repo.undoLast(eventId)
        if (ok) {
            refresh()
            startTickerIfNeeded()
            WidgetRecordHelper.refreshAll(this)
        } else {
            Snackbar.make(binding.root, R.string.toast_no_undo, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("timestamp", text))
        Snackbar.make(binding.root, getString(R.string.toast_copied, text), Snackbar.LENGTH_LONG).show()
    }

    /** 行复制文案 */
    private fun rowCopyText(row: Row): String = when (row) {
        is Row.Point -> TimeFormat.full(row.millis) + "  (Unix 秒: " + (row.millis / 1000) + ")"
        is Row.Interval -> {
            val end = row.iv.end?.let { TimeFormat.full(it) } ?: "进行中"
            "${TimeFormat.full(row.iv.start)} – $end（${TimeFormat.duration(row.iv.duration())}）"
        }
    }

    private fun confirmDelete(row: Row) {
        val msg = rowCopyText(row)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_record_title)
            .setMessage(getString(R.string.delete_record_msg, msg))
            .setPositiveButton(R.string.delete) { _, _ ->
                when (row) {
                    is Row.Point -> repo.deleteRecord(eventId, row.millis)
                    is Row.Interval -> repo.deleteIntervalByStart(eventId, row.iv.start)
                }
                refresh()
                startTickerIfNeeded()
                WidgetRecordHelper.refreshAll(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 批量管理（区间 v1 不开放批量，只支持单点长按删） ----------

    private fun enterSelectionMode() {
        if (currentEvent()?.isInterval == true) {
            Snackbar.make(binding.root, "区间事件暂不支持批量管理，长按单段可删除", Snackbar.LENGTH_SHORT).show()
            return
        }
        if (adapter.items.isEmpty()) {
            Snackbar.make(binding.root, R.string.toast_empty, Snackbar.LENGTH_SHORT).show()
            return
        }
        selectionMode = true
        selected.clear()
        binding.selectionBar.visibility = View.VISIBLE
        binding.recyclerRecords.updatePadding(bottom = 140)
        adapter.notifyDataSetChanged()
        updateSelectionUI()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selected.clear()
        binding.selectionBar.visibility = View.GONE
        binding.recyclerRecords.updatePadding(bottom = 24)
        adapter.notifyDataSetChanged()
    }

    private fun toggleSelection(row: Row) {
        if (selected.contains(row)) selected.remove(row) else selected.add(row)
        adapter.notifyDataSetChanged()
        updateSelectionUI()
    }

    private fun toggleSelectAll() {
        if (selected.size == adapter.items.size) selected.clear()
        else selected.addAll(adapter.items)
        adapter.notifyDataSetChanged()
        updateSelectionUI()
    }

    private fun updateSelectionUI() {
        val n = selected.size
        binding.tvSelectedCount.text = getString(R.string.batch_selected_count, n)
        binding.btnDeleteSelected.text = getString(R.string.batch_delete_with_count, n)
        binding.btnSelectAll.setText(
            if (n == adapter.items.size && adapter.items.isNotEmpty()) R.string.batch_deselect_all
            else R.string.batch_select_all
        )
    }

    private fun deleteSelected() {
        if (selected.isEmpty()) {
            Snackbar.make(binding.root, R.string.batch_none_selected, Snackbar.LENGTH_SHORT).show()
            return
        }
        // 批量只用于点事件
        val millisSet = selected.filterIsInstance<Row.Point>().map { it.millis }.toSet()
        val n = millisSet.size
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.batch_delete_title)
            .setMessage(getString(R.string.batch_delete_msg, n))
            .setPositiveButton(R.string.delete) { _, _ ->
                repo.deleteRecords(eventId, millisSet)
                exitSelectionMode()
                refresh()
                WidgetRecordHelper.refreshAll(this)
                Snackbar.make(binding.root, getString(R.string.batch_deleted, n), Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 菜单 ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, 1, 0, R.string.menu_edit_event)
        menu.add(Menu.NONE, 2, 0, R.string.export_records)
        menu.add(Menu.NONE, 3, 0, R.string.menu_clear_records)
        menu.add(Menu.NONE, 4, 0, R.string.menu_delete_event)
        val batchItem = menu.add(Menu.NONE, 5, 0, R.string.menu_batch)
        batchItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        batchItem.setIcon(R.drawable.ic_check_box)
        menu.add(Menu.NONE, 6, 0, R.string.menu_stats)
        menu.add(Menu.NONE, 7, 0, R.string.menu_timeline)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> showEditDialog()
            2 -> startExport()
            3 -> confirmClear()
            4 -> confirmDeleteEvent()
            5 -> enterSelectionMode()
            6 -> startActivity(Intent(this, StatsActivity::class.java)
                .putExtra(StatsActivity.EXTRA_FOCUS_EVENT_ID, eventId))
            7 -> startActivity(Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_TIMELINE, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showEditDialog() {
        val event = currentEvent() ?: return
        val dlg = DialogEditEventBinding.inflate(layoutInflater)
        val colorAdapter = ColorAdapter(event.color)
        dlg.recyclerColors.layoutManager = GridLayoutManager(this, 6)
        dlg.recyclerColors.adapter = colorAdapter
        dlg.editName.setText(event.name)

        // 记录方式回填
        dlg.chipPoint.isChecked = event.type == TimestampEvent.TYPE_POINT
        dlg.chipInterval.isChecked = event.type == TimestampEvent.TYPE_INTERVAL
        dlg.tvTypeHint.setText(
            if (event.isInterval) R.string.event_type_interval_hint else R.string.event_type_point_hint
        )
        dlg.chipPoint.setOnCheckedChangeListener { _, c -> if (c) dlg.tvTypeHint.setText(R.string.event_type_point_hint) }
        dlg.chipInterval.setOnCheckedChangeListener { _, c -> if (c) dlg.tvTypeHint.setText(R.string.event_type_interval_hint) }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_edit_event_title)
            .setView(dlg.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dlg.editName.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    dlg.inputName.error = getString(R.string.toast_name_required)
                    return@setOnClickListener
                }
                val type = if (dlg.chipInterval.isChecked) TimestampEvent.TYPE_INTERVAL else TimestampEvent.TYPE_POINT
                repo.updateEvent(event.id, name, colorAdapter.selected, type)
                dialog.dismiss()
                refresh()
                WidgetRecordHelper.refreshAll(this@EventDetailActivity)
            }
        }
        dialog.show()
    }

    private fun confirmClear() {
        val event = currentEvent() ?: return
        val count = if (event.isInterval) repo.intervalCount(eventId) else repo.recordCount(eventId)
        if (count == 0) {
            Snackbar.make(binding.root, R.string.toast_empty, Snackbar.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_records_title)
            .setMessage(getString(R.string.clear_records_msg, count))
            .setPositiveButton(R.string.delete) { _, _ ->
                if (event.isInterval) repo.clearIntervals(eventId) else repo.clearRecords(eventId)
                refresh()
                startTickerIfNeeded()
                WidgetRecordHelper.refreshAll(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteEvent() {
        val event = currentEvent() ?: return
        val count = if (event.isInterval) repo.intervalCount(eventId) else repo.recordCount(eventId)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_event_title)
            .setMessage(getString(R.string.delete_event_msg, event.name, count))
            .setPositiveButton(R.string.delete) { _, _ ->
                repo.deleteEvent(eventId)
                WidgetRecordHelper.refreshAll(this)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 导出 ----------

    private fun startExport() {
        val event = currentEvent() ?: return
        val count = if (event.isInterval) repo.intervalCount(eventId) else repo.recordCount(eventId)
        if (count == 0) {
            Snackbar.make(binding.root, R.string.toast_empty, Snackbar.LENGTH_SHORT).show()
            return
        }
        val now = TimeFormat.full(System.currentTimeMillis()).replace(':', '-').replace(' ', '_')
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/comma-separated-values"
            putExtra(Intent.EXTRA_TITLE, "时间戳记录_$now.csv")
        }
        exportLauncher.launch(intent)
    }

    private fun exportTo(uri: Uri) {
        try {
            val event = currentEvent() ?: return
            val os = contentResolver.openOutputStream(uri) ?: return
            val writer = OutputStreamWriter(os, StandardCharsets.UTF_8)
            val name = event.name.replace(",", "，")
            if (event.isInterval) {
                val list = repo.getIntervals(eventId)
                writer.write("\uFEFF事件,开始,结束,时长(毫秒),时长\n")
                list.forEach { iv ->
                    val end = iv.end
                    val endStr = end?.let { TimeFormat.full(it) } ?: "进行中"
                    val dur = iv.duration()
                    writer.write("$name,${TimeFormat.full(iv.start)},$endStr,${dur},${TimeFormat.duration(dur)}\n")
                }
            } else {
                val records = repo.getRecords(eventId)
                writer.write("\uFEFF事件,记录时间,Unix秒\n")
                records.forEach { millis ->
                    writer.write("$name,${TimeFormat.full(millis)},${millis / 1000}\n")
                }
            }
            writer.flush()
            writer.close()
            Snackbar.make(binding.root, getString(R.string.toast_exported, adapter.items.size), Snackbar.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Snackbar.make(binding.root, R.string.toast_export_failed, Snackbar.LENGTH_SHORT).show()
        }
    }

    // ---------- 列表 ----------

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {

        val items = mutableListOf<Row>()

        fun submit(list: List<Row>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = items[position]
            val inSelection = this@EventDetailActivity.selectionMode
            holder.b.tvIndex.text = String.format(Locale.getDefault(), "#%d", items.size - position)
            when (row) {
                is Row.Point -> {
                    holder.b.tvTime.text = TimeFormat.full(row.millis)
                    holder.b.tvUnix.text = getString(R.string.unix_label, row.millis / 1000)
                }
                is Row.Interval -> {
                    val iv = row.iv
                    val endStr = iv.end?.let { TimeFormat.hm(it) } ?: getString(R.string.timeline_ongoing, TimeFormat.duration(iv.duration()))
                    holder.b.tvTime.text = "${TimeFormat.hm(iv.start)} – $endStr（${TimeFormat.duration(iv.duration())}）"
                    holder.b.tvUnix.text = "开始 Unix: ${iv.start / 1000}"
                }
            }
            holder.b.tvCopy.visibility = if (inSelection) View.GONE else View.VISIBLE
            holder.b.cbSelect.visibility = if (inSelection) View.VISIBLE else View.GONE
            holder.b.cbSelect.isChecked = selected.contains(row)
            if (inSelection) {
                holder.b.root.setOnClickListener { toggleSelection(row) }
                holder.b.root.setOnLongClickListener { toggleSelection(row); true }
            } else {
                holder.b.tvCopy.setOnClickListener { copy(rowCopyText(row)) }
                holder.b.root.setOnClickListener { copy(rowCopyText(row)) }
                holder.b.root.setOnLongClickListener { confirmDelete(row); true }
            }
        }

        inner class VH(val b: ItemRecordBinding) : RecyclerView.ViewHolder(b.root)
    }

    // ---------- 进行中跳动 ----------

    private fun startTickerIfNeeded() {
        val ev = currentEvent() ?: return
        val ongoing = ev.takeIf { it.isInterval }?.let { repo.ongoingInterval(it.id) }
        if (ongoing != null && !ticking) {
            ticking = true
            ticker.post(tickRunnable)
        } else if (ongoing == null) {
            stopTicker()
        }
    }

    private fun stopTicker() {
        ticking = false
        ticker.removeCallbacks(tickRunnable)
    }
}
