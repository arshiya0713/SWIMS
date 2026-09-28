package com.swims.app.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.swims.app.databinding.ItemAchievementBinding
import com.swims.app.ml.Achievement

class AchievementAdapter : ListAdapter<Achievement, AchievementAdapter.VH>(Diff()) {

    inner class VH(private val b: ItemAchievementBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(a: Achievement) {
            b.tvBadge.text = a.emoji
            b.tvBadgeLabel.text = a.title
            val alpha = if (a.unlocked) 1f else 0.32f
            b.tvBadge.alpha = alpha
            b.tvBadgeLabel.alpha = alpha
            b.root.setOnClickListener {
                val state = if (a.unlocked) "Unlocked!" else "Locked"
                Toast.makeText(b.root.context, "${a.emoji} ${a.title} — $state ${a.description}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAchievementBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class Diff : DiffUtil.ItemCallback<Achievement>() {
        override fun areItemsTheSame(a: Achievement, b: Achievement) = a.id == b.id
        override fun areContentsTheSame(a: Achievement, b: Achievement) = a == b
    }
}
