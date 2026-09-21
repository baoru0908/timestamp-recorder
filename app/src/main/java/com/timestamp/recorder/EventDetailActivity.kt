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
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

        // ⋮ 玻璃菜单的动作 id（原先由系统 options menu 的 itemId 承担）
        private const val ACT_EDIT = 1
        private const val ACT_EXPORT = 2
        private const val ACT_CLEAR = 3
        private const val ACT_DELETE_EVENT = 4
        private const val ACT_STATS = 6
        private const val ACT_TIMELINE = 7
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
        // 返回键交给菜单回调统一裁决（见 menuBackCallback）
        onBackPressedDispatcher.addCallback(this, menuBackCallback)
        repo = EventRepository(this)
        eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)

        binding.recyclerRecords.layoutManager = LinearLayoutManager(this)
        binding.recyclerRecords.adapter = adapter
        // 大屏内容列居中（手机上是空操作）：平板/折叠屏/横屏下把主按钮与记录列收成居中一列
        centerContentColumn(
            binding.contentHost,
            resources.getDimensionPixelSize(R.dimen.screen_horizontal_padding)
        )

        binding.btnRecord.setOnClickListener { onPrimaryAction() }
        binding.btnUndo.setOnClickListener { undo() }

        binding.btnMore.setOnClickListener { toggleActionMenu(it) }
        binding.btnBatch.setOnClickListener { enterSelectionMode() }

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
        // 玻璃菜单是独立窗口，Activity 退后台时不会被自动收掉，必须手动关（与主页一致）。
        // ⚠️ 用 closeNow（不播退场动画）：页面已在退场，再叠一层菜单动画只会显脏
        GlassMenu.closeNow(this)
        super.onPause()
    }

    private fun currentEvent(): TimestampEvent? = repo.getEvent(eventId)

    private fun refresh() {
        val event = currentEvent()
        if (event == null) { finish(); return }
        binding.toolbar.title = event.name

        // 主按钮前景色：默认白字，底色极亮（纯白/明黄，相对亮度 > 0.60）时才转近黑 —— 与事件卡同一套规则
        // ⚠️ 2026-09-21 阈值 0.45→0.60（解读 B：白字一致性优先）：
        //    琥珀 #F9A825 已改回白字，不再属于"过亮"这一档
        val onPrimary = EventColors.onColor(event.color)
        binding.btnRecord.setTextColor(onPrimary)
        binding.btnRecord.iconTint = ColorStateList.valueOf(onPrimary)

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
                getString(R.string.detail_ongoing, TimeFormat.duration(this, ongoing.duration()))
            } else if (intervals.isNotEmpty()) {
                getString(R.string.interval_last, TimeFormat.duration(this, intervals.first().duration()))
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
                    getString(R.string.toast_stopped, event.name, TimeFormat.duration(this, ended?.duration() ?: 0)),
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
        is Row.Point -> getString(R.string.unix_copy_text, TimeFormat.full(row.millis), row.millis / 1000)
        is Row.Interval -> {
            val end = row.iv.end?.let { TimeFormat.full(it) } ?: getString(R.string.interval_ongoing)
            getString(R.string.interval_range_text, TimeFormat.full(row.iv.start), end, TimeFormat.duration(this, row.iv.duration()))
        }
    }

    private fun confirmDelete(row: Row) {
        val msg = rowCopyText(row)
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Timestamp_Dialog)
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
            Snackbar.make(binding.root, R.string.interval_batch_unsupported, Snackbar.LENGTH_SHORT).show()
            return
        }
        if (adapter.items.isEmpty()) {
            Snackbar.make(binding.root, R.string.toast_empty, Snackbar.LENGTH_SHORT).show()
            return
        }
        selectionMode = true
        selected.clear()
        showSelectionBar()
        // ⚠️ 底部让位只声明"额外需要多少"，绝对值由 BaseActivity 统一算（base + extra + 导航栏 inset）。
        //    曾经这里写 updatePadding(bottom = 140) —— **裸像素**（≈37dp）且退出时设成 24px（≈6dp），
        //    把导航栏 inset 一起冲掉，导致退出批量后最后一条记录停在手势条下面点不到
        //    （2026-09-21 设计评审 I-2 真机实证）。
        setScrollExtraBottomPadding(resources.getDimensionPixelSize(R.dimen.selection_bar_clearance))
        adapter.notifyDataSetChanged()
        updateSelectionUI()
    }

    /** 批量操作栏浮出：位移 + 淡入，避免"突然出现一整条"的硬切（与菜单展开同语言） */
    private fun showSelectionBar() {
        val bar = binding.selectionBar
        val offset = resources.getDimensionPixelSize(R.dimen.space_6).toFloat()
        bar.animate().cancel()
        bar.visibility = View.VISIBLE
        bar.alpha = 0f
        bar.translationY = offset
        bar.animate().alpha(1f).translationY(0f)
            .setDuration(180L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    /** 批量操作栏收起：动画结束后彻底 GONE，并把状态复位（下次浮出仍从头播） */
    private fun hideSelectionBar() {
        val bar = binding.selectionBar
        val offset = resources.getDimensionPixelSize(R.dimen.space_6).toFloat()
        bar.animate().cancel()
        bar.animate().alpha(0f).translationY(offset)
            .setDuration(140L)
            .withEndAction {
                bar.visibility = View.GONE
                bar.alpha = 1f
                bar.translationY = 0f
            }
            .start()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selected.clear()
        hideSelectionBar()
        setScrollExtraBottomPadding(0)
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
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Timestamp_Dialog)
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

    // ---------- 菜单（统一走 GlassMenu 玻璃卡片，不再用系统溢出菜单）----------

    /**
     * 事件详情页的动作菜单。
     *
     * ⚠️ 这里曾经用 `onCreateOptionsMenu` —— 那是**系统溢出菜单**：白底（深色下纯深灰）、
     * 直角、系统字重、系统弹窗动效，和 App 的玻璃语言完全不是一个东西。
     * 现在改成与主页「⋮」同源的 [GlassMenu]：
     * 工具栏右侧两枚自绘按钮 = ☑ 批量管理（图标动作）+ ⋮ 其余动作。
     *
     * ⚠️ 走 [GlassMenu.toggle]：**再点一次同一个 ⋮ 就收起**，且收起是展开动画的时间反演
     * （同 pivot、同时长 190ms、缓动 Decelerate↔Accelerate）；收起动画进行中再点一次
     * 会反向展开回来，全程不重建窗口，所以不会闪。
     */
    private fun toggleActionMenu(anchor: View) {
        val handle = GlassMenu.toggle(
            activity = this,
            anchor = anchor,
            items = listOf(
                GlassMenu.Item(R.drawable.ic_menu_edit, R.string.menu_edit_event, ACT_EDIT),
                GlassMenu.Item(R.drawable.ic_menu_export, R.string.export_records, ACT_EXPORT),
                GlassMenu.Item(R.drawable.ic_menu_stats, R.string.menu_stats, ACT_STATS),
                GlassMenu.Item(R.drawable.ic_tab_timeline, R.string.menu_timeline, ACT_TIMELINE),
                GlassMenu.Item(R.drawable.ic_menu_clear, R.string.menu_clear_records, ACT_CLEAR),
                GlassMenu.Item(
                    R.drawable.ic_menu_delete, R.string.menu_delete_event,
                    ACT_DELETE_EVENT, destructive = true
                )
            ),
            onDismiss = { tintMenuAnchor(anchor, false) }
        ) { action ->
            when (action) {
                ACT_EDIT -> showEditDialog()
                ACT_EXPORT -> startExport()
                ACT_STATS -> startActivity(
                    Intent(this, StatsActivity::class.java)
                        .putExtra(StatsActivity.EXTRA_FOCUS_EVENT_ID, eventId)
                )
                ACT_TIMELINE -> startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_OPEN_TIMELINE, true)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                ACT_CLEAR -> confirmClear()
                ACT_DELETE_EVENT -> confirmDeleteEvent()
            }
        }
        // 点亮状态跟着菜单真实状态走：再次点击（已在收起）立刻"灭灯"，反馈不等动画
        tintMenuAnchor(anchor, handle.isOpen)
    }

    /** 菜单展开时把锚点按钮点亮/复位（与主页同一套做法） */
    private fun tintMenuAnchor(anchor: View, open: Boolean) {
        val tint = com.google.android.material.color.MaterialColors.getColor(
            binding.root,
            if (open) com.google.android.material.R.attr.colorPrimary
            else com.google.android.material.R.attr.colorOnSurface
        )
        (anchor as? android.widget.ImageView)?.imageTintList =
            ColorStateList.valueOf(tint)
    }

    /**
     * 菜单开着时，**返回键先收菜单**（走同一套退场动画），而不是直接退出本页。
     *
     * 玻璃菜单的窗口是 `FLAG_NOT_FOCUSABLE` 的（不吃焦点，否则会挡住下方列表的触摸），
     * 按键到不了那个窗口 —— 不接管的话，菜单还开着、一按返回却退回上一页，很不直觉。
     */
    private val menuBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (GlassMenu.closeAnimated(this@EventDetailActivity)) return
            // 没有菜单在开 → 临时让开，交回系统默认的返回行为
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            isEnabled = true
        }
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

        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Timestamp_Dialog)
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
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Timestamp_Dialog)
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
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Timestamp_Dialog)
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
            putExtra(Intent.EXTRA_TITLE, getString(R.string.csv_filename_prefix) + "_$now.csv")
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
                writer.write("\uFEFF" + getString(R.string.csv_header_interval) + "\n")
                list.forEach { iv ->
                    val end = iv.end
                    val endStr = end?.let { TimeFormat.full(it) } ?: getString(R.string.interval_ongoing)
                    val dur = iv.duration()
                    writer.write("$name,${TimeFormat.full(iv.start)},$endStr,${dur},${TimeFormat.duration(this, dur)}\n")
                }
            } else {
                val records = repo.getRecords(eventId)
                writer.write("\uFEFF" + getString(R.string.csv_header_point) + "\n")
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
                    val endStr = iv.end?.let { TimeFormat.hm(it) } ?: getString(R.string.timeline_ongoing, TimeFormat.duration(this@EventDetailActivity, iv.duration()))
                    holder.b.tvTime.text = getString(R.string.interval_range_text, TimeFormat.hm(iv.start), endStr, TimeFormat.duration(this@EventDetailActivity, iv.duration()))
                    holder.b.tvUnix.text = getString(R.string.unix_start_label, iv.start / 1000)
                }
            }
            holder.b.tvCopy.visibility = if (inSelection) View.GONE else View.VISIBLE
            holder.b.cbSelect.visibility = if (inSelection) View.VISIBLE else View.GONE
            holder.b.cbSelect.isChecked = selected.contains(row)
            // 勾选框本身没有文字，读屏软件只会念"复选框"：把这条记录的时间作为它的标签（评审 A-1）
            holder.b.cbSelect.contentDescription = holder.b.tvTime.text
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
