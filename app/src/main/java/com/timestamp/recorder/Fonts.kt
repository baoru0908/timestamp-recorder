package com.timestamp.recorder

import android.content.res.AssetManager
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.RequiresApi

/**
 * MiSans 字体加载器（小米官方字体，全球免费商用，出处已在「关于」页注明）。
 *
 * 字体文件位于 app/src/main/assets/fonts/，由 tools/subset_misans.py 从官方全量字体
 * 裁出「GB2312 + 拉丁 + 常用标点」子集（单字重约 1.7MB，全量约 7.5MB）：
 *   MiSans-Regular.ttf / MiSans-Medium.ttf / MiSans-Bold.ttf
 *
 * 子集体量小但覆盖面有限，所以额外用系统字体做兜底链路：遇到生僻字或 emoji 时
 * 自动回退到系统字体，不会出现豆腐块。若字体文件整体缺失，则完全回退系统字体，
 * 不影响编译与运行。
 */
object Fonts {
    private var regular: Typeface? = null
    private var medium: Typeface? = null
    private var bold: Typeface? = null
    private var initialized = false

    private fun load(context: android.content.Context) {
        if (initialized) return
        initialized = true
        val assets = context.assets
        regular = loadOne(assets, "fonts/MiSans-Regular.ttf")
        medium = loadOne(assets, "fonts/MiSans-Medium.ttf")
        bold = loadOne(assets, "fonts/MiSans-Bold.ttf")
    }

    private fun loadOne(assets: AssetManager, path: String): Typeface? {
        val plain = try {
            Typeface.createFromAsset(assets, path)
        } catch (_: Exception) {
            return null // 文件缺失：该字重回退 null，由调用处降级
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return plain
        return withSystemFallback(assets, path, plain)
    }

    /**
     * 给自定义字体挂上系统字体作为兜底（API 29+ 的 CustomFallbackBuilder）。
     * 子集字体缺字时自动回退系统字体，不会出现豆腐块；任何异常都退回基础字体。
     *
     * 标注 @RequiresApi：本方法用到 Font.Builder / Typeface.CustomFallbackBuilder（均 API 29+），
     * 调用方 [loadOne] 已用 `SDK_INT < Q` 提前返回做守卫。加注解后 lint 的 NewApi 不再误报。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun withSystemFallback(assets: AssetManager, path: String, plain: Typeface): Typeface {
        return try {
            val family = FontFamily.Builder(Font.Builder(assets, path).build()).build()
            Typeface.CustomFallbackBuilder(family).setSystemFallback("sans-serif").build()
        } catch (_: Exception) {
            plain
        }
    }

    /** 将 MiSans 应用到某个视图树（含其子视图里的所有文字） */
    fun applyTo(root: View) {
        load(root.context)
        if (regular == null && medium == null && bold == null) return
        walk(root)
    }

    private fun walk(view: View) {
        if (view is TextView) {
            view.typeface = when {
                view.paint.isFakeBoldText -> bold ?: medium ?: regular
                view.typeface?.isBold == true -> bold ?: medium ?: regular
                else -> regular
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
    }
}
