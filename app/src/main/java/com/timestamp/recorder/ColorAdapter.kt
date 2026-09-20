package com.timestamp.recorder

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.timestamp.recorder.databinding.ItemColorBinding

/** 分类染色颜色选择器（事件编辑对话框共用）。 */
class ColorAdapter(initial: Int) : RecyclerView.Adapter<ColorAdapter.VH>() {

    var selected: Int = initial
        private set

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemColorBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = EventColors.palette.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val color = EventColors.palette[position]
        holder.b.tvPlus.visibility = View.GONE
        holder.b.viewColor.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (color == selected) {
                val stroke = (holder.b.root.resources.displayMetrics.density * 3).toInt()
                setStroke(stroke, 0xFFFFFFFF.toInt())
            }
        }
        holder.b.root.setOnClickListener {
            selected = color
            notifyDataSetChanged()
        }
    }

    inner class VH(val b: ItemColorBinding) : RecyclerView.ViewHolder(b.root)
}
