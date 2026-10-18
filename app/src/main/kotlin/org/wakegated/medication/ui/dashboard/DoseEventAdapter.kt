package org.wakegated.medication.ui.dashboard

import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.wakegated.medication.data.entity.DoseEventEntity
import org.wakegated.medication.data.entity.MedicationEntity
import org.wakegated.medication.data.model.DoseState
import org.wakegated.medication.databinding.ItemDoseEventBinding
import java.text.SimpleDateFormat
import java.util.*

class DoseEventAdapter(
    private val onItemClick: (DoseEventEntity, MedicationEntity) -> Unit,
    private val onTakeClick: ((DoseEventEntity, MedicationEntity) -> Unit)? = null,
    private val onSkipClick: ((DoseEventEntity, MedicationEntity) -> Unit)? = null
) : ListAdapter<Pair<DoseEventEntity, MedicationEntity>, DoseEventAdapter.DoseViewHolder>(DiffCallback) {

    class DoseViewHolder(val binding: ItemDoseEventBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DoseViewHolder {
        val binding = ItemDoseEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DoseViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DoseViewHolder, position: Int) {
        val (event, med) = getItem(position)
        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())

        holder.binding.tvMedName.text = med.name
        holder.binding.tvDoseDetails.text = "${med.dosage} ${med.unit} • Scheduled ${sdf.format(Date(event.scheduledAt))}".trim()
        holder.binding.tvDoseNotes.text = med.notes.ifBlank { if (med.wakeGated) "Wake-gated reminder" else "Standard reminder" }

        // State Badge styling
        when (event.state) {
            DoseState.SCHEDULED -> {
                holder.binding.tvDoseStateBadge.text = "SCHEDULED"
                holder.binding.tvDoseStateBadge.setBackgroundColor(Color.parseColor("#E0F2FE"))
                holder.binding.tvDoseStateBadge.setTextColor(Color.parseColor("#0369A1"))
                holder.binding.tvMedName.paintFlags = holder.binding.tvMedName.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                holder.binding.layoutInlineActions.visibility = View.VISIBLE
            }
            DoseState.DUE -> {
                holder.binding.tvDoseStateBadge.text = "● DUE (Waiting for unlock)"
                holder.binding.tvDoseStateBadge.setBackgroundColor(Color.parseColor("#FEF3C7"))
                holder.binding.tvDoseStateBadge.setTextColor(Color.parseColor("#B45309"))
                holder.binding.tvMedName.paintFlags = holder.binding.tvMedName.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                holder.binding.layoutInlineActions.visibility = View.VISIBLE
            }
            DoseState.RINGING -> {
                holder.binding.tvDoseStateBadge.text = "⏰ RINGING"
                holder.binding.tvDoseStateBadge.setBackgroundColor(Color.parseColor("#FEE2E2"))
                holder.binding.tvDoseStateBadge.setTextColor(Color.parseColor("#DC2626"))
                holder.binding.tvMedName.paintFlags = holder.binding.tvMedName.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                holder.binding.layoutInlineActions.visibility = View.VISIBLE
            }
            DoseState.TAKEN -> {
                val actionTime = event.actionTimestamp?.let { " at ${sdf.format(Date(it))}" } ?: ""
                holder.binding.tvDoseStateBadge.text = "✓ TAKEN$actionTime"
                holder.binding.tvDoseStateBadge.setBackgroundColor(Color.parseColor("#DCFCE7"))
                holder.binding.tvDoseStateBadge.setTextColor(Color.parseColor("#15803D"))
                holder.binding.tvMedName.paintFlags = holder.binding.tvMedName.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                holder.binding.layoutInlineActions.visibility = View.GONE
            }
            DoseState.SKIPPED -> {
                holder.binding.tvDoseStateBadge.text = "✗ SKIPPED"
                holder.binding.tvDoseStateBadge.setBackgroundColor(Color.parseColor("#F3F4F6"))
                holder.binding.tvDoseStateBadge.setTextColor(Color.parseColor("#6B7280"))
                holder.binding.tvMedName.paintFlags = holder.binding.tvMedName.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                holder.binding.layoutInlineActions.visibility = View.GONE
            }
            DoseState.RESCHEDULED -> {
                holder.binding.tvDoseStateBadge.text = "↺ SNOOZED"
                holder.binding.tvDoseStateBadge.setBackgroundColor(Color.parseColor("#EDE9FE"))
                holder.binding.tvDoseStateBadge.setTextColor(Color.parseColor("#6D28D9"))
                holder.binding.tvMedName.paintFlags = holder.binding.tvMedName.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                holder.binding.layoutInlineActions.visibility = View.GONE
            }
            DoseState.MISSED -> {
                holder.binding.tvDoseStateBadge.text = "● MISSED"
                holder.binding.tvDoseStateBadge.setBackgroundColor(Color.parseColor("#FEF3C7"))
                holder.binding.tvDoseStateBadge.setTextColor(Color.parseColor("#B45309"))
                holder.binding.tvMedName.paintFlags = holder.binding.tvMedName.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                holder.binding.layoutInlineActions.visibility = View.GONE
            }
        }

        // Inline Action Listeners
        holder.binding.btnInlineTake.setOnClickListener {
            onTakeClick?.invoke(event, med)
        }
        holder.binding.btnInlineSkip.setOnClickListener {
            onSkipClick?.invoke(event, med)
        }

        holder.itemView.setOnClickListener {
            onItemClick(event, med)
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<Pair<DoseEventEntity, MedicationEntity>>() {
        override fun areItemsTheSame(
            oldItem: Pair<DoseEventEntity, MedicationEntity>,
            newItem: Pair<DoseEventEntity, MedicationEntity>
        ): Boolean = oldItem.first.doseEventId == newItem.first.doseEventId

        override fun areContentsTheSame(
            oldItem: Pair<DoseEventEntity, MedicationEntity>,
            newItem: Pair<DoseEventEntity, MedicationEntity>
        ): Boolean = oldItem == newItem
    }
}
