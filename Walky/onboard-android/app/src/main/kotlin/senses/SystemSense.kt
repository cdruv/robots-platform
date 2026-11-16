package com.vadymsidorov.walky.senses

import android.content.Context
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.vadymsidorov.walky.core.events.Percept
import com.vadymsidorov.walky.core.events.SystemStatus
import com.vadymsidorov.walky.core.senses.Sense

/** Battery, charging and thermal state as [SystemStatus]: once a second, and at once when the thermal status changes. */
class SystemSense(context: Context) : Sense {
    override val name = "system"

    private val context = context.applicationContext
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var post: ((Percept) -> Unit)? = null
    private val thermal = PowerManager.OnThermalStatusChangedListener { report() }
    private val tick = object : Runnable {
        override fun run() {
            report()
            main.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun start(post: (Percept) -> Unit) {
        main.post {
            if (this.post != null) return@post
            this.post = post
            power.addThermalStatusListener(context.mainExecutor, thermal)
            tick.run()
        }
    }

    override fun stop() {
        main.post {
            if (post == null) return@post
            post = null
            main.removeCallbacks(tick)
            power.removeThermalStatusListener(thermal)
        }
    }

    private fun report() {
        val post = post ?: return
        post(SystemStatus(
            batteryPct = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            charging = battery.isCharging,
            thermalStatus = power.currentThermalStatus,
        ))
    }

    private companion object {
        const val INTERVAL_MS = 1_000L
    }
}
