package com.timestamp.recorder

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.timestamp.recorder.databinding.ItemColorBinding

/**
 * 分类染色颜色选择器（新建 / 编辑事件对话框共用）。
 *
 * 列表 = **第 0 格「自定义颜色」 + 23 格预设色**（共 24 格、6 列 → 正好 4 行整）。
 *
 * ## 布局约定（2026-09-21 主人要求）
 * 1. **自定义入口放在第一个**：它是一类"新的选择"而不是"最后一个颜料的补充"，
 *    放首位更容易被发现（此前放在末尾，且因为没接线而根本不显示）。
 * 2. 预设色 **24 → 23**（去掉 #78909C 浅蓝灰，它与「蓝灰」区分度最低），
 *    这样 1 + 23 = 24 格刚好铺满 4 行，不会出现孤零零的最后一格。
 * 3. **纹理/描边跟随底色**：选中环的颜色由色块亮度决定（[EventColors.onColor]），
 *    极亮块用深色环、其余用白环（主人反馈"纹理要根据背景颜色调整，提升可见性"）。
 *    ⚠️ 2026-09-21 阈值 0.45→0.60（解读 B：白字一致性优先）后**仅明黄仍为深环**；
 *    琥珀 #F9A825 的环由近黑变白（8.67:1 → 1.97:1），选中态在该色块上偏弱 ——
 *    这是"白字一致性优先"的已知代价。薄荷 / 青 / 浅蓝 / 橙 / 亮青这 5 色的环
 *    新旧规则下都是白环，**非本次引入**，勿计入本次回归面。
 *
 * ## ⚠️ 自定义色盘曾经是"死格子"
 * `item_color.xml` 里的 `tvPlus`（「＋」）在 3d8ded6 加入后，同一次提交里就把它
 * `visibility = GONE` 且 `getItemCount()` 只返回 palette 数量 —— 这一格**从未被渲染过**
 * （`git log -S "tvPlus.visibility = View.VISIBLE"` 为空）。现在真正接上 [ColorPickerDialog]。
 */
class ColorAdapter(initial: Int) : RecyclerView.Adapter<ColorAdapter.VH>() {

    companion object {
        /** 第 0 格是「自定义颜色」，预设色从 1 开始 */
        const val CUSTOM_POSITION = 0
    }

    var selected: Int = initial
        private set

    /** 当前选中的是不是自定义色（不在预设色板里） */
    private val isCustomSelected: Boolean get() = selected !in EventColors.palette

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemColorBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = EventColors.palette.size + 1

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (position == CUSTOM_POSITION) {
            bindCustomEntry(holder)
            return
        }

        val color = EventColors.palette[position - 1]
        val isSelected = color == selected
        val d = holder.b.root.resources.displayMetrics.density
        holder.b.tvPlus.visibility = View.GONE
        holder.b.viewColor.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (isSelected) {
                // 选中环跟着色块亮度走：极亮块（明黄）给深环，其余给白环。
                // ⚠️ 2026-09-21 阈值 0.45→0.60 后，琥珀 #F9A825 的环由近黑变白（8.67→1.97:1），
                //    选中态在该色块上偏弱；其余 22 色的环色与旧规则一致，未变化。
                setStroke((3 * d).toInt(), EventColors.onColor(color))
            }
        }
        // 纯色圆点对读屏软件只是一串"空按钮"：补上颜色名 + 选中态。
        // 顺序与 EventColors.palette 严格一一对应（event_color_names 数组）。
        val names = holder.b.root.resources.getStringArray(R.array.event_color_names)
        val name = names.getOrNull(position - 1).orEmpty()
        holder.b.root.contentDescription = holder.b.root.resources.getString(
            if (isSelected) R.string.cd_color_option_selected else R.string.cd_color_option,
            name
        )
        // 让系统无障碍服务也能播报"已选中"（与底部 Tab 同一套做法）
        holder.b.root.isSelected = isSelected
        holder.b.root.setOnClickListener {
            selected = color
            notifyDataSetChanged()
        }
    }

    /**
     * 第 0 格的「自定义颜色」：
     * - 未选中 → 空心圆 + 「＋」（描边/底色取主题的 outline / surfaceVariant，
     *   在浅色与深色主题下都清晰可见；此前写死 13%/40% 白，浅色主题下几乎看不见）；
     * - 已选中 → 直接显示用户挑的那个颜色 + 自适应选中环（与预设色表达完全一致）。
     */
    private fun bindCustomEntry(holder: VH) {
        val view = holder.b.root
        val d = view.resources.displayMetrics.density
        val custom = isCustomSelected

        // 选中时不再显示「＋」：白字压在用户自选的浅色上会看不清；
        // 直接给色块 + 环，和预设色的表达方式统一。
        holder.b.tvPlus.visibility = if (custom) View.GONE else View.VISIBLE

        val outline = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOutline, 0x8A8A8A)
        val surfaceVariant = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorSurfaceVariant, 0xE0E0E0
        )
        holder.b.viewColor.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (custom) {
                setColor(selected)
                setStroke((3 * d).toInt(), EventColors.onColor(selected))
            } else {
                // 空心圆：极淡的主题面底色 + 主题 outline 描边（两套主题下都看得清）
                setColor(withAlpha(surfaceVariant, 0x40))
                setStroke((1.5f * d).toInt().coerceAtLeast(2), outline)
            }
        }
        // 「＋」也跟着（未选中时的）底色走：深色主题给白、浅色主题给深灰
        holder.b.tvPlus.setTextColor(
            if (custom) EventColors.onColor(selected) else outline
        )

        holder.b.root.contentDescription =
            if (custom) {
                view.resources.getString(R.string.cd_color_custom_selected)
            } else {
                view.resources.getString(R.string.cd_color_custom)
            }
        holder.b.root.isSelected = custom
        holder.b.root.setOnClickListener {
            // 用 root 的 context（就是承载对话框的 Activity），取色器才能拿到 Material 主题
            ColorPickerDialog(holder.b.root.context, selected) { picked ->
                selected = picked
                notifyDataSetChanged()
            }.show()
        }
    }

    inner class VH(val b: ItemColorBinding) : RecyclerView.ViewHolder(b.root)
}
