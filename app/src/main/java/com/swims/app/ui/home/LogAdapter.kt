package com.swims.app.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.swims.app.data.model.DrinkType
import com.swims.app.data.model.IntakeLog
import com.swims.app.databinding.ItemLogBinding
import java.text.SimpleDateFormat
import java.util.*

class LogAdapter(
    private val onDelete: (IntakeLog) -> Unit
) : ListAdapter<IntakeLog, LogAdapter.LogVH>(DiffCb()) {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    inner class LogVH(private val b: ItemLogBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(log: IntakeLog) {
            val type = DrinkType.from(log.drinkType)
            b.tvDrinkIcon.text = type.emoji
            // Show credited ml when the drink doesn't hydrate 100%
            b.tvAmount.text =
                if (log.hydrationMl in 1 until log.amountMl)
                    "${log.amountMl} ml → ${log.hydrationMl} ml"
                else "${log.amountMl} ml"
            b.tvTime.text = timeFmt.format(Date(log.timestampMs))
            val note = log.note?.trim().orEmpty()
            val sub = when {
                note.isNotEmpty() && type != DrinkType.WATER -> "${type.label} · $note"
                note.isNotEmpty() -> note
                type != DrinkType.WATER -> type.label
                else -> ""
            }
            b.tvNote.text = sub
            b.tvNote.visibility = if (sub.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            b.btnDelete.setOnClickListener { onDelete(log) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogVH {
        val b = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LogVH(b)
    }

    override fun onBindViewHolder(holder: LogVH, position: Int) = holder.bind(getItem(position))

    class DiffCb : DiffUtil.ItemCallback<IntakeLog>() {
        override fun areItemsTheSame(a: IntakeLog, b: IntakeLog) = a.id == b.id
        override fun areContentsTheSame(a: IntakeLog, b: IntakeLog) = a == b
    }
}
