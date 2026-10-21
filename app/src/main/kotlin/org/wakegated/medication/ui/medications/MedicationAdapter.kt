package org.wakegated.medication.ui.medications

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.wakegated.medication.data.entity.MedicationEntity
import org.wakegated.medication.data.entity.ScheduleEntity
import org.wakegated.medication.databinding.ItemMedicationBinding
import org.wakegated.medication.domain.model.ScheduleCalculator

class MedicationAdapter(
    private val onItemClick: (MedicationEntity) -> Unit,
    private val onEditClick: ((MedicationEntity) -> Unit)? = null,
    private val onDeleteClick: ((MedicationEntity) -> Unit)? = null
) : ListAdapter<Pair<MedicationEntity, List<ScheduleEntity>>, MedicationAdapter.MedViewHolder>(DiffCallback) {

    class MedViewHolder(val binding: ItemMedicationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MedViewHolder {
        val binding = ItemMedicationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MedViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MedViewHolder, position: Int) {
        val (med, schedules) = getItem(position)

        holder.binding.tvMedName.text = med.name

        val (timeStr, daysStr) = if (schedules.isNotEmpty()) {
            val sched = schedules.first()
            val mins = sched.timeInMinutes
            val h = mins / 60
            val m = mins % 60
            val amPm = if (h >= 12) "PM" else "AM"
            val displayH = if (h == 0 || h == 12) 12 else h % 12
            Pair(
                String.format("%02d:%02d %s", displayH, m, amPm),
                ScheduleCalculator.formatDaysSummary(sched.daysOfWeek)
            )
        } else {
            Pair("No schedule", "Inactive")
        }

        val wakeGatedStr = if (med.wakeGated) "Wake-Gated: ON" else "Standard: ON"
        holder.binding.tvMedDetails.text = "${med.dosage} ${med.unit} • $timeStr\n$daysStr • $wakeGatedStr".trim()
        holder.binding.tvMedNotes.text = med.notes.ifBlank { "Sound: ${med.soundTitle}" }

        // Stock quantity & Low-Stock warning
        if (med.stockQuantity != null) {
            val stockVal = med.stockQuantity
            // Stock is a count of doses/tablets, not a dosage in µg/mg/IU.
            holder.binding.tvStock.text = "Stock: ${if (stockVal % 1.0 == 0.0) stockVal.toInt() else stockVal}"
            holder.binding.tvStock.visibility = View.VISIBLE

            if (stockVal <= 5.0) {
                holder.binding.tvLowStockWarning.text = "⚠️ Low Stock: ${stockVal.toInt()} left"
                holder.binding.tvLowStockWarning.visibility = View.VISIBLE
            } else {
                holder.binding.tvLowStockWarning.visibility = View.GONE
            }
        } else {
            holder.binding.tvStock.text = "Stock: ∞"
            holder.binding.tvStock.visibility = View.VISIBLE
            holder.binding.tvLowStockWarning.visibility = View.GONE
        }

        holder.binding.btnEditMed.setOnClickListener {
            onEditClick?.invoke(med)
        }
        holder.binding.btnDeleteMed.setOnClickListener {
            onDeleteClick?.invoke(med)
        }
        holder.itemView.setOnClickListener {
            onItemClick(med)
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<Pair<MedicationEntity, List<ScheduleEntity>>>() {
        override fun areItemsTheSame(
            oldItem: Pair<MedicationEntity, List<ScheduleEntity>>,
            newItem: Pair<MedicationEntity, List<ScheduleEntity>>
        ): Boolean = oldItem.first.medicationId == newItem.first.medicationId

        override fun areContentsTheSame(
            oldItem: Pair<MedicationEntity, List<ScheduleEntity>>,
            newItem: Pair<MedicationEntity, List<ScheduleEntity>>
        ): Boolean = oldItem == newItem
    }
}
