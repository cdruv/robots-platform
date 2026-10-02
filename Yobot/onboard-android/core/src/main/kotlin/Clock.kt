package com.vadymsidorov.yobot.core

/** Time source, injectable so logic and tests never read the system clock directly. */
interface Clock {
    /** Wall-clock milliseconds since the epoch. */
    fun nowMs(): Long

    /** Monotonic nanoseconds, for intervals only. */
    fun monoNs(): Long

    object System : Clock {
        override fun nowMs(): Long = java.lang.System.currentTimeMillis()
        override fun monoNs(): Long = java.lang.System.nanoTime()
    }
}
