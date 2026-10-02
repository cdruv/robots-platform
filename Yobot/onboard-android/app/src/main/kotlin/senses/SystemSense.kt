package com.vadymsidorov.yobot.senses

import android.content.Context
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.vadymsidorov.yobot.core.events.Percept
import com.vadymsidorov.yobot.core.events.SystemStatus
import com.vadymsidorov.yobot.core.senses.Sense

/** Battery and thermal status every [periodMs], plus immediately on thermal changes. */
class SystemSense(context: Context, private val periodMs: Long = 30_000) : Sense {
    override val name = "system"

    private val battery = context.getSystemService(BatteryManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var post: ((Percept) -> Unit)? = null

    private val poll = object : Runnable {
        override fun run() {
            report()
            handler.postDelayed(this, periodMs)
        }
    }
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { report() }

    override fun start(post: (Percept) -> Unit) {
        handler.post {
            this.post = post
            power.addThermalStatusListener(handler::post, thermalListener)
            handler.removeCallbacks(poll)
            poll.run()
        }
    }

    override fun stop() {
        handler.post {
            handler.removeCallbacks(poll)
            power.removeThermalStatusListener(thermalListener)
            post = null
        }
    }

    private fun report() {
        post?.invoke(
            SystemStatus(
                batteryPct = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                charging = battery.isCharging,
                thermalStatus = power.currentThermalStatus,
            ),
        )
    }
}
