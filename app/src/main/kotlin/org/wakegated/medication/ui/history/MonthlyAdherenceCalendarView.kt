package org.wakegated.medication.ui.history

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import org.wakegated.medication.R
import org.wakegated.medication.domain.model.DayAdherenceLevel
import org.wakegated.medication.domain.model.MonthAdherenceSummary
import java.text.SimpleDateFormat
import java.util.*

class MonthlyAdherenceCalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var displayCalendar = Calendar.getInstance()
    private var selectedDayOfMonth: Int? = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
    private var currentSummary: MonthAdherenceSummary? = null

    private lateinit var tvMonthTitle: TextView
    private lateinit var btnPrevMonth: ImageButton
    private lateinit var btnNextMonth: ImageButton
    private lateinit var gridDays: GridLayout

    var onMonthChangeListener: ((year: Int, month: Int) -> Unit)? = null
    var onDayClickListener: ((year: Int, month: Int, dayOfMonth: Int, dayStartMs: Long, dayEndMs: Long) -> Unit)? = null

    init {
        orientation = VERTICAL
        setupView()
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).toInt()
    }

    private fun setupView() {
        setPadding(dpToPx(12f), dpToPx(10f), dpToPx(12f), dpToPx(10f))

        // 1. Month Header (e.g. < August 2026 >)
        val headerLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(6f)
            }
        }

        btnPrevMonth = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dpToPx(8f), dpToPx(8f), dpToPx(8f), dpToPx(8f))
            setOnClickListener {
                displayCalendar.add(Calendar.MONTH, -1)
                selectedDayOfMonth = null
                onMonthChangeListener?.invoke(displayCalendar.get(Calendar.YEAR), displayCalendar.get(Calendar.MONTH))
                updateCalendarDisplay()
            }
        }

        tvMonthTitle = TextView(context).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.primary))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        btnNextMonth = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_media_next)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dpToPx(8f), dpToPx(8f), dpToPx(8f), dpToPx(8f))
            setOnClickListener {
                displayCalendar.add(Calendar.MONTH, 1)
                selectedDayOfMonth = null
                onMonthChangeListener?.invoke(displayCalendar.get(Calendar.YEAR), displayCalendar.get(Calendar.MONTH))
                updateCalendarDisplay()
            }
        }

        headerLayout.addView(btnPrevMonth)
        headerLayout.addView(tvMonthTitle)
        headerLayout.addView(btnNextMonth)
        addView(headerLayout)

        // 2. Weekday Header (Mon Tue Wed Thu Fri Sat Sun)
        val weekdayLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(4f)
            }
        }
        val weekdays = arrayOf("M", "T", "W", "T", "F", "S", "S")
        for (w in weekdays) {
            val tv = TextView(context).apply {
                text = w
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#9CA3AF"))
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }
            weekdayLayout.addView(tv)
        }
        addView(weekdayLayout)

        // 3. Days Grid (7 columns)
        gridDays = GridLayout(context).apply {
            columnCount = 7
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        addView(gridDays)

        updateCalendarDisplay()
    }

    fun setMonthSummary(summary: MonthAdherenceSummary) {
        currentSummary = summary
        displayCalendar.set(Calendar.YEAR, summary.year)
        displayCalendar.set(Calendar.MONTH, summary.month)
        updateCalendarDisplay()
    }

    private fun updateCalendarDisplay() {
        val sdf = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        tvMonthTitle.text = sdf.format(displayCalendar.time)

        gridDays.removeAllViews()

        val cal = displayCalendar.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        // First day of week (Monday = 1, Sunday = 7 in ISO)
        val firstDayCal = cal.get(Calendar.DAY_OF_WEEK)
        val startOffset = when (firstDayCal) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 6
            else -> 0
        }

        val cellHeightPx = dpToPx(32f)

        // Add blank padding cells for offset
        for (i in 0 until startOffset) {
            val blank = View(context).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = cellHeightPx
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
            }
            gridDays.addView(blank)
        }

        val statusMap = currentSummary?.dayStatusMap ?: emptyMap()
        val todayCal = Calendar.getInstance()
        val isCurrentMonth = (todayCal.get(Calendar.YEAR) == displayCalendar.get(Calendar.YEAR) && todayCal.get(Calendar.MONTH) == displayCalendar.get(Calendar.MONTH))
        val todayDay = todayCal.get(Calendar.DAY_OF_MONTH)

        for (day in 1..daysInMonth) {
            val status = statusMap[day] ?: DayAdherenceLevel.EMPTY
            val isSelected = (selectedDayOfMonth == day)
            val isToday = isCurrentMonth && (day == todayDay)

            val card = MaterialCardView(context).apply {
                radius = dpToPx(8f).toFloat()
                elevation = if (isSelected) 3f else 0f
                strokeWidth = if (isSelected) dpToPx(2f) else if (isToday) dpToPx(1f) else 0
                strokeColor = if (isSelected) ContextCompat.getColor(context, R.color.primary) else Color.parseColor("#9CA3AF")

                val bgColor = when (status) {
                    DayAdherenceLevel.PERFECT -> Color.parseColor("#DCFCE7") // Crisp Green
                    DayAdherenceLevel.PARTIAL -> Color.parseColor("#FEF3C7") // Warm Amber
                    DayAdherenceLevel.SKIPPED -> Color.parseColor("#FEE2E2") // Soft Red
                    DayAdherenceLevel.EMPTY -> if (isToday) Color.parseColor("#F3F4F6") else Color.TRANSPARENT
                }
                setCardBackgroundColor(bgColor)

                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = cellHeightPx
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dpToPx(2f), dpToPx(2f), dpToPx(2f), dpToPx(2f))
                }
            }

            val tvDay = TextView(context).apply {
                text = day.toString()
                textSize = 11f
                gravity = Gravity.CENTER
                typeface = if (isToday || isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(
                    when (status) {
                        DayAdherenceLevel.PERFECT -> Color.parseColor("#15803D")
                        DayAdherenceLevel.PARTIAL -> Color.parseColor("#B45309")
                        DayAdherenceLevel.SKIPPED -> Color.parseColor("#DC2626")
                        DayAdherenceLevel.EMPTY -> Color.parseColor("#4B5563")
                    }
                )
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            }

            card.addView(tvDay)
            card.setOnClickListener {
                selectedDayOfMonth = day
                updateCalendarDisplay()

                val dayCal = displayCalendar.clone() as Calendar
                dayCal.set(Calendar.DAY_OF_MONTH, day)
                dayCal.set(Calendar.HOUR_OF_DAY, 0)
                dayCal.set(Calendar.MINUTE, 0)
                dayCal.set(Calendar.SECOND, 0)
                dayCal.set(Calendar.MILLISECOND, 0)
                val startMs = dayCal.timeInMillis
                val endMs = startMs + 86_400_000L

                onDayClickListener?.invoke(
                    dayCal.get(Calendar.YEAR),
                    dayCal.get(Calendar.MONTH),
                    day,
                    startMs,
                    endMs
                )
            }

            gridDays.addView(card)
        }
    }
}
