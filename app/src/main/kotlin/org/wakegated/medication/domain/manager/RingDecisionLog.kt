package org.wakegated.medication.domain.manager

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Append-only ring-decision log. Lines: epochMs|source|interactive|keyguardLocked|fromOverLockscreen|lastUnlockSinceDue|corroborated|decision|doseId
 * Bounded: >2000 lines → rewrite keeping newest 1000.
 */
object RingDecisionLog {

    private const val TAG = "RingDecisionLog"
    private const val LOG_FILE = "medwake_events.log"
    private const val MAX_LINES = 2000
    private const val KEEP_LINES = 1000

    private val trimming = AtomicBoolean(false)

    fun log(
        context: Context,
        source: String,
        interactive: Boolean,
        keyguardLocked: Boolean,
        fromOverLockscreen: Boolean,
        lastUnlockSinceDue: Long?,
        corroborated: Boolean,
        decision: String,
        doseId: Long
    ) {
        val line = "${System.currentTimeMillis()}|$source|$interactive|$keyguardLocked|$fromOverLockscreen|$lastUnlockSinceDue|$corroborated|$decision|$doseId\n"
        try {
            val file = File(context.filesDir, LOG_FILE)
            file.appendText(line)
            maybeTrim(file)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write ring decision log", e)
        }
    }

    /**
     * Log a userPresent event (from MedWakeApp receiver).
     */
    fun logUserPresent(context: Context) {
        val line = "${System.currentTimeMillis()}|userPresent|||||||\n"
        try {
            val file = File(context.filesDir, LOG_FILE)
            file.appendText(line)
            maybeTrim(file)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write userPresent log", e)
        }
    }

    fun readRecent(context: Context, maxLines: Int = 100): String {
        return try {
            val file = File(context.filesDir, LOG_FILE)
            if (!file.exists()) return "No ring-decision log yet."
            val lines = file.readLines()
            lines.takeLast(maxLines).joinToString("\n")
        } catch (e: Exception) {
            "Error reading log: ${e.message}"
        }
    }

    private fun maybeTrim(file: File) {
        if (trimming.compareAndSet(false, true)) {
            try {
                if (file.exists() && file.readLines().size > MAX_LINES) {
                    val kept = file.readLines().takeLast(KEEP_LINES)
                    file.writeText(kept.joinToString("\n") + "\n")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Log trim failed", e)
            } finally {
                trimming.set(false)
            }
        }
    }
}
