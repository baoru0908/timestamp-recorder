package com.timestamp.recorder

import android.os.Bundle
import android.view.View
import com.timestamp.recorder.databinding.ActivityAboutBinding

/** 关于 / 开源引导页：版本信息、GitHub 入口、字体与许可出处 */
class AboutActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupChrome(binding.toolbar, binding.appBar, binding.root, R.string.about_title, showBack = true, scrollContent = binding.scrollContent)
        // 液态玻璃顶栏（与主页同源）：内容滚动时从玻璃底下穿过实时折射
        installLiquidTopGlass(binding.topGlass, binding.appBar, binding.scrollContent)

        binding.tvVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        // 大屏内容列居中（手机上是空操作）
        centerContentColumn(
            binding.scrollContent.getChildAt(0) as View,
            resources.getDimensionPixelSize(R.dimen.screen_horizontal_padding)
        )

        // 开发者卡片整块可点但没有文字标签，读屏软件只会念出一串文字：
        // 给它一句完整的动作描述（评审 A-1）
        binding.cardDeveloper.contentDescription = getString(R.string.cd_dev_card, getString(R.string.dev_name))
        binding.btnGithub.setOnClickListener { openExternalUrl(Links.REPO) }
        binding.btnFeedback.setOnClickListener { openExternalUrl(Links.ISSUES) }
        // 开发者卡片 → GitHub 个人主页（头像与署名离线打包，跳转交给系统浏览器）
        binding.cardDeveloper.setOnClickListener { openExternalUrl(Links.PROFILE) }
    }
}
