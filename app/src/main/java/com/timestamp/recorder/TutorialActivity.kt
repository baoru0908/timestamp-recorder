package com.timestamp.recorder

import android.content.Intent
import android.os.Bundle
import com.timestamp.recorder.databinding.ActivityTutorialBinding

/**
 * 使用教程（App 内置，离线可读）。
 *
 * 之前教程跳 GitHub README —— 国内经常连不上，用户白跑一趟。
 * 现在把最常用、最短的内容内置进来：三步上手 + 小组件重点引导（本 App 的核心特色），
 * GitHub 仅作为联网时的补充入口。
 */
class TutorialActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityTutorialBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupChrome(
            binding.toolbar, binding.appBar, binding.root,
            R.string.tutorial_section_title, showBack = true,
            scrollContent = binding.scrollContent
        )
        // 液态玻璃顶栏（与主页同源）：内容滚动时从玻璃底下穿过实时折射
        installLiquidTopGlass(binding.topGlass, binding.appBar, binding.scrollContent)
        // 大屏内容列居中（手机上是空操作）
        centerContentColumn(
            binding.scrollContent.getChildAt(0) as android.view.View,
            resources.getDimensionPixelSize(R.dimen.screen_horizontal_padding)
        )

        // 小组件一键添加在设置页（含机型权限引导）
        binding.btnGoSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // 联网场景下的完整图文（README「使用教程」章节）
        binding.btnOpenGithub.setOnClickListener { openExternalUrl(Links.TUTORIAL) }
    }
}
