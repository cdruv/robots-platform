package com.vadymsidorov.walky

import android.app.Application

class WalkyApplication : Application() {
    lateinit var runtime: Runtime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = Runtime(this)
    }
}
