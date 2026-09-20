package com.timestamp.recorder

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.timestamp.recorder.databinding.ActivitySettingsBinding
import java.nio.charset.StandardCharsets

/** 设置页：快捷按钮（+）位置左/中/右切换、小组件圆角、一键添加、关于 */
class SettingsActivity : BaseActivity() {

    companion object {
        const val PREFS = "tsr_settings"
        /** 事件排序方式：手动（拖拽）/ 按最近记录时间 */
        const val KEY_SORT_MODE = "sort_mode"
        const val SORT_MANUAL = "manual"
        const val SORT_RECENT = "recent"
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: android.content.SharedPreferences

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            try {
                val json = BackupHelper.exportJson(EventRepository(this))
                contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(json.toByteArray(StandardCharsets.UTF_8))
                }
                Snackbar.make(binding.root, R.string.backup_exported, Snackbar.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Snackbar.make(binding.root, R.string.backup_failed, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            try {
                val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
                val data = BackupHelper.parse(json)
                if (data == null) {
                    Snackbar.make(binding.root, R.string.backup_invalid, Snackbar.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                val totalRecords = data.records.values.sumOf { it.size }
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.backup_import_confirm_title)
                    .setMessage(getString(R.string.backup_import_confirm_msg, data.events.size, totalRecords))
                    .setPositiveButton(R.string.backup_import_confirm) { _, _ ->
                        EventRepository(this).replaceAllData(data)
                        WidgetRecordHelper.refreshAll(this)
                        Snackbar.make(binding.root, R.string.backup_imported, Snackbar.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } catch (_: Exception) {
                Snackbar.make(binding.root, R.string.backup_failed, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // 统一工具栏 + 沉浸式 + 字体（由 BaseActivity 处理）
        setupChrome(binding.toolbar, binding.appBar, binding.root, R.string.settings_title, showBack = true, scrollContent = binding.scrollContent)
        // 液态玻璃顶栏（与主页同源）：内容滚动时从玻璃底下穿过实时折射
        installLiquidTopGlass(binding.topGlass, binding.appBar, binding.scrollContent)

        // 小组件圆角档位
        when (WidgetPrefs.corner(this)) {
            8 -> binding.rbCorner8.isChecked = true
            24 -> binding.rbCorner24.isChecked = true
            32 -> binding.rbCorner32.isChecked = true
            else -> binding.rbCorner16.isChecked = true
        }
        binding.radioCorner.setOnCheckedChangeListener { _, checkedId ->
            val corner = when (checkedId) {
                com.timestamp.recorder.R.id.rbCorner8 -> 8
                com.timestamp.recorder.R.id.rbCorner24 -> 24
                com.timestamp.recorder.R.id.rbCorner32 -> 32
                else -> 16
            }
            WidgetPrefs.setCorner(this, corner)
            WidgetRecordHelper.refreshAll(this)
            Snackbar.make(binding.root, R.string.corner_applied, Snackbar.LENGTH_SHORT).show()
        }

        // 一键添加到桌面（标准 API：支持的 ROM 弹确认框钉到桌面；不支持的 ROM 退回手动添加指引）
        binding.btnPinAll.setOnClickListener { pinWidget(TimestampWidgetProvider::class.java) }
        binding.btnPinSingle.setOnClickListener { pinWidget(WidgetSingleProvider::class.java) }
        binding.btnPinCapsule.setOnClickListener { pinWidget(WidgetCapsuleProvider::class.java) }
        binding.btnPinCustom.setOnClickListener { pinWidget(WidgetCustomProvider::class.java) }

        // 权限 / 设置引导：**全机型显示**（各 ROM 对「一键添加」的限制不同），
        // 文案与跳转目标按机型切换 —— 小米 / 红米推荐先授「桌面快捷方式」权限
        // （HyperOS 不授予就静默无反应），其他机型走通用系统设置兜底。
        val isXiaomi = Build.MANUFACTURER.contains("xiaomi", true) ||
                Build.MANUFACTURER.contains("redmi", true)
        binding.permBox.visibility = View.VISIBLE
        binding.btnPermSettings.setText(
            if (isXiaomi) R.string.pin_perm_btn_miui else R.string.pin_perm_btn
        )
        binding.permHint.setText(
            if (isXiaomi) R.string.pin_perm_hint_miui else R.string.pin_perm_hint
        )
        binding.btnPermSettings.setOnClickListener { openPermSettings(isXiaomi) }

        // 事件排序方式
        when (prefs.getString(KEY_SORT_MODE, SORT_MANUAL)) {
            SORT_RECENT -> binding.rbRecent.isChecked = true
            else -> binding.rbManual.isChecked = true
        }
        binding.radioSort.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == com.timestamp.recorder.R.id.rbRecent) SORT_RECENT else SORT_MANUAL
            prefs.edit().putString(KEY_SORT_MODE, mode).apply()
        }

        // 关于 / 开源引导
        binding.btnAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        // 使用教程：交给系统浏览器打开 README 的教程章节
        binding.btnTutorial.setOnClickListener { startActivity(Intent(this, TutorialActivity::class.java)) }

        // 数据备份 / 恢复（走系统 SAF，不申请存储权限）
        binding.btnExportBackup.setOnClickListener {
            val now = TimeFormat.full(System.currentTimeMillis()).replace(':', '-').replace(' ', '_')
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "TimestampRecorder_backup_$now.json")
            }
            exportBackupLauncher.launch(intent)
        }
        binding.btnImportBackup.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            }
            importBackupLauncher.launch(intent)
        }
    }

    /**
     * 一键申请把小组件钉到桌面（Android 8.0+）。
     *
     * 系统小部件列表里各 App 的排序由启动器决定，开发者无法通过任何 Manifest 属性干预，
     * 所以用这个官方 API 让用户不必去列表里翻找。
     * 返回 false 表示当前桌面不支持（部分 ROM 如此），此时提示用户手动长按桌面添加。
     */
    private fun pinWidget(provider: Class<*>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = AppWidgetManager.getInstance(this)
            // 先问桌面到底支不支持：部分 ROM（真我 realme UI 等）不实现这个接口，
            // 有的甚至返回 true 却毫无反应 —— 所以既要预检能力，也要判请求返回值。
            val supported = try {
                manager.isRequestPinAppWidgetSupported
            } catch (_: Exception) {
                false
            }
            if (supported) {
                val requested = try {
                    manager.requestPinAppWidget(ComponentName(this, provider), null, null)
                } catch (_: Exception) {
                    false
                }
                if (requested) {
                    Toast.makeText(this, R.string.toast_pin_ok, Toast.LENGTH_LONG).show()
                    return
                }
            }
        }
        // 桌面未开放该能力（小米 HyperOS / 真我 realme UI 等实测无效）：退回手动添加指引
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pin_guide_title)
            .setMessage(R.string.pin_guide_msg)
            .setPositiveButton(R.string.i_know, null)
            .show()
    }

    /**
     * 打开权限 / 应用设置。
     * 小米：跳 MIUI 权限编辑页（「桌面快捷方式」开关在里面；MIUI 各版本活动名不固定，
     * 按「权限编辑页 → 权限列表页」依次尝试）；
     * 其他机型 / 全部打不开 → 系统应用详情页兜底（所有 ROM 都有）。
     */
    private fun openPermSettings(isXiaomi: Boolean) {
        if (!isXiaomi) {
            openAppDetails()
            return
        }
        val candidates = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", packageName)
            },
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsActivity"
                )
                putExtra("extra_pkgname", packageName)
            }
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
                // 这个入口在当前 MIUI 版本上不存在，换下一个
            }
        }
        openAppDetails()
    }

    /** 系统应用详情页：所有 ROM 都有的通用兜底入口 */
    private fun openAppDetails() {
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            Toast.makeText(this, R.string.toast_no_browser, Toast.LENGTH_SHORT).show()
        }
    }
}
