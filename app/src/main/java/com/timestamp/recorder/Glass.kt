package com.timestamp.recorder

import android.content.Context
import android.graphics.Color
import com.qmdeve.liquidglass.widget.LiquidGlassView

/**
 * 液态玻璃的统一参数装配（把散落在各页面的魔法数收成一处）。
 *
 * ## ⚠️ 色彩空间铁律：tint 走 **sRGB**，不要做线性换算
 *
 * 着色器里的混色是 `mix(color.rgb, tintColor, tintAlpha)`，而 `color.rgb` 来自
 * `content.eval()` —— 也就是屏幕上的 sRGB 采样值。证据是同一文件里的 `saturateColor()`：
 *
 * ```glsl
 * half3 lin = toLinearSrgb(color.rgb);          // 进来先转线性
 * half3 sat = fromLinearSrgb(mix(gray, lin, amount));  // 算完再转回 sRGB
 * ```
 *
 * 一对 `toLinearSrgb` / `fromLinearSrgb` 恰好说明**工作空间是 sRGB**（否则这层转换是多余的）。
 * 所以 tintColor 必须是 **sRGB 0~1**，直接由颜色资源除以 255 即可。
 *
 * > 2026-09-21 踩过的坑：曾"想当然"把颜色资源多做一次 sRGB→线性换算，
 * > 于是近黑色 `#0E1014`（0.055）被压成 0.004 —— 再叠加 72% 浓度，
 * > 玻璃直接变成**实心黑**，主页胶囊岛与顶部玻璃栏的液态玻璃观感全丢。
 * > 现在恢复为 sRGB 直传 + 两档浓度（见下）。
 *
 * ## 两档玻璃：观感优先 / 可读性优先
 *
 * | 场景 | 着色浓度 token | 着色颜色 token | 配套 |
 * |---|---|---|---|
 * | 主页胶囊岛 + 顶部玻璃栏 | `glass_tint_alpha` | `glass_tint` | — |
 * | 二级页顶栏 | `glass_tint_alpha_solid` | `glass_tint_solid` | `bg_top_scrim` 遮罩 |
 *
 * 二级页顶栏的条目会从标题下面穿过，光靠玻璃压不住（玻璃再厚也只是一层薄膜），
 * 所以那边改用「遮罩 + 更厚的膜」把标题钉死；主页则保留纯粹的薄玻璃观感。
 */
object Glass {

    /** shader 需要的 sRGB 三通道 + 浓度 */
    class Params(val tintR: Float, val tintG: Float, val tintB: Float, val tintAlpha: Float)

    /**
     * 从颜色资源算出 tint（sRGB 0~1，**不做线性换算**）。
     *
     * @param solid true = 二级页顶栏那档（近黑 + 厚膜，配 bg_top_scrim）
     */
    fun params(context: Context, solid: Boolean): Params {
        val tint = context.getColor(if (solid) R.color.glass_tint_solid else R.color.glass_tint)
        val alphaRes = if (solid) R.integer.glass_tint_alpha_solid else R.integer.glass_tint_alpha
        return Params(
            tintR = Color.red(tint) / 255f,
            tintG = Color.green(tint) / 255f,
            tintB = Color.blue(tint) / 255f,
            tintAlpha = context.resources.getInteger(alphaRes) / 100f
        )
    }

    /**
     * 给一块玻璃装配「模糊 / 折射 / 着色」三件套。
     *
     * @param cornerRadiusDp 圆角（dp；0 = 直角，顶栏用）
     * @param refractionRes  折射高度的尺寸资源（顶栏 / 胶囊岛 / 加号圆钮各一档）
     * @param solid          true = 可读性优先那档（二级页顶栏）
     * @param tintAlphaOverride 覆盖着色浓度；传 0f 表示"只折射 + 模糊、不上色"
     *        （加号圆钮用这一档：小圆形按钮涂满色会丢掉玻璃质感）
     */
    fun apply(
        view: LiquidGlassView,
        context: Context,
        content: android.view.ViewGroup,
        cornerRadiusDp: Float,
        refractionRes: Int,
        solid: Boolean = false,
        tintAlphaOverride: Float? = null
    ) {
        val res = context.resources
        val d = res.displayMetrics.density
        view.bind(content)
        view.setCornerRadius(cornerRadiusDp * d)
        view.setBlurRadius(
            res.getDimension(R.dimen.glass_blur_radius)
                .coerceAtMost(res.getDimension(R.dimen.glass_blur_max))
        )
        view.setRefractionHeight(res.getDimension(refractionRes))
        val p = params(context, solid)
        view.setTintColorRed(p.tintR)
        view.setTintColorGreen(p.tintG)
        view.setTintColorBlue(p.tintB)
        view.setTintAlpha(tintAlphaOverride ?: p.tintAlpha)
    }
}
