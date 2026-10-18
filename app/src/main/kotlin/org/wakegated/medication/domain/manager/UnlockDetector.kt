package org.wakegated.medication.domain.manager

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

class UnlockDetector(private val context: Context) {

    private val keyguardManager by lazy {
        context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    }

    private val powerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }

    /**
     * Returns true if the device screen is on and interactive.
     * This is the primary gate for wake-gated alarm presentation.
     */
    fun isInteractive(): Boolean = powerManager?.isInteractive ?: false

    /**
     * Returns true if the keyguard (lock screen) is currently showing.
     */
    fun isKeyguardLocked(): Boolean = keyguardManager?.isKeyguardLocked ?: false

    /**
     * Diagnostic triple for debug logging: (interactive, isDeviceLocked, isKeyguardLocked).
     */
    fun debugState(): Triple<Boolean, Boolean, Boolean> = Triple(
        isInteractive(),
        keyguardManager?.isDeviceLocked ?: false,
        isKeyguardLocked()
    )
}
