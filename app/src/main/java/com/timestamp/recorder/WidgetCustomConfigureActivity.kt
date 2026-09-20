package com.timestamp.recorder

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.io.File
import java.io.FileOutputStream

/** 自定义小组件配置页：标题 / 背景色（含自定义取色）/ 背景图片 / 勾选事件 */
class WidgetCustomConfigureActivity : BaseActivity() {

    private lateinit var repo: EventRepository
    private var widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private val bgOptions = intArrayOf(
        0xFF2D2D3A.toInt(), // 深灰蓝（默认）
        0xFF121212.toInt(), // 纯黑
        0xFF1E88E5.toInt(),
        0xFF43A047.toInt(),
        0xFFD81B60.toInt(),
        0xFFFB8C00.toInt()
    )
    private var chosenBg: Int = WidgetPrefs.CUSTOM_BG_DEFAULT
    private var imagePath: String = ""
    private val checked = HashSet<Long>()
    private lateinit var events: List<TimestampEvent>

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            val saved = copyImageToInternal(uri)
            if (saved != null) { imagePath = saved; showPreview(saved); Toast.makeText(this, "已设置背景图", Toast.LENGTH_SHORT).show() }
            else Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_custom_configure)
        repo = EventRepository(this)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        setupChrome(findViewById(R.id.toolbar), null, findViewById(R.id.root),
            R.string.widget_custom_config_title, showBack = true)

        chosenBg = WidgetPrefs.customBg(this, widgetId)
        imagePath = WidgetPrefs.customImage(this, widgetId)
        findViewById<android.widget.EditText>(R.id.editTitle).setText(WidgetPrefs.customTitle(this, widgetId))
        checked.addAll(WidgetPrefs.customEvents(this, widgetId))
        showPreview(imagePath)

        buildBgChips()

        findViewById<Button>(R.id.btnPickImage).setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<Button>(R.id.btnClearImage).setOnClickListener {
            if (imagePath.isNotBlank()) { runCatching { File(imagePath).delete() } }
            imagePath = ""
            showPreview("")
            Toast.makeText(this, "已去掉背景图", Toast.LENGTH_SHORT).show()
        }

        events = repo.getEvents()
        buildEventRows(events)

        findViewById<View>(R.id.btnSelectAll).setOnClickListener {
            checked.addAll(events.map { it.id }); buildEventRows(events)
        }
        findViewById<View>(R.id.btnClearAll).setOnClickListener {
            checked.clear(); buildEventRows(events)
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val title = findViewById<android.widget.EditText>(R.id.editTitle).text.toString().trim()
            if (checked.isEmpty()) { Toast.makeText(this, "请至少勾选一个事件", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            WidgetPrefs.saveCustom(this, widgetId, title, chosenBg, checked)
            WidgetPrefs.setCustomImage(this, widgetId, imagePath)
            val manager = AppWidgetManager.getInstance(this)
            manager.updateAppWidget(widgetId, WidgetCustomProvider.buildRemoteViews(this, widgetId))
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }

    private fun buildBgChips() {
        val group = findViewById<ChipGroup>(R.id.groupBg)
        group.removeAllViews()
        val d = resources.displayMetrics.density
        bgOptions.forEach { color ->
            val chip = Chip(this).apply {
                text = ""
                isCheckable = true
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(color)
                chipStrokeWidth = (if (color == chosenBg) 3 else 1).toFloat() * d
                chipStrokeColor = android.content.res.ColorStateList.valueOf(Color.WHITE)
                isChecked = color == chosenBg
                setOnClickListener { chosenBg = color; buildBgChips() }
            }
            group.addView(chip)
        }
        val isCustom = chosenBg !in bgOptions
        val custom = Chip(this).apply {
            isCheckable = true
            if (isCustom) {
                text = ""
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(chosenBg)
                chipStrokeWidth = 3f * d
                chipStrokeColor = android.content.res.ColorStateList.valueOf(Color.WHITE)
            } else {
                text = "＋自定义"
                chipStrokeWidth = 1f * d
                chipStrokeColor = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            setOnClickListener {
                ColorPickerDialog(this@WidgetCustomConfigureActivity, chosenBg) { picked ->
                    chosenBg = picked; buildBgChips()
                }.show()
            }
        }
        group.addView(custom)
    }

    /** 所有事件平铺进滚动区，整页可滚动，不再被高度挤压成两行 */
    private fun buildEventRows(list: List<TimestampEvent>) {
        val container = findViewById<LinearLayout>(R.id.containerEvents)
        container.removeAllViews()
        val d = resources.displayMetrics.density
        list.forEach { ev ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
            }
            val dot = View(this).apply {
                val s = (14 * d).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = (12 * d).toInt() }
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ev.color) }
            }
            val name = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                textSize = 15f
                text = ev.name
            }
            val box = CheckBox(this).apply { isChecked = ev.id in checked }
            box.setOnCheckedChangeListener { _, isChk -> if (isChk) checked.add(ev.id) else checked.remove(ev.id) }
            row.addView(dot); row.addView(name); row.addView(box)
            container.addView(row)
        }
    }

    private fun copyImageToInternal(uri: Uri): String? {
        return try {
            val dest = File(filesDir, "widget_bg_$widgetId.jpg")
            contentResolver.openInputStream(uri).use { input ->
                FileOutputStream(dest).use { out -> input!!.copyTo(out) }
            }
            dest.absolutePath
        } catch (e: Exception) { null }
    }

    private fun showPreview(path: String) {
        val iv = findViewById<android.widget.ImageView>(R.id.ivPreview)
        if (path.isBlank()) { iv.setImageBitmap(null); return }
        try {
            val opts = android.graphics.BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            android.graphics.BitmapFactory.decodeFile(path, opts)
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / sample > 800) sample *= 2
            opts.inJustDecodeBounds = false; opts.inSampleSize = sample
            iv.setImageBitmap(android.graphics.BitmapFactory.decodeFile(path, opts))
        } catch (e: Exception) { iv.setImageBitmap(null) }
    }
}
