package org.wakegated.medication.data.model

enum class DoseState {
    SCHEDULED,
    DUE,
    RINGING,
    TAKEN,
    SKIPPED,
    RESCHEDULED,
    MISSED;

    fun isResolved(): Boolean = this == TAKEN || this == SKIPPED || this == RESCHEDULED
    fun isPending(): Boolean = this == SCHEDULED || this == DUE || this == RINGING
}
