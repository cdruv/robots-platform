package com.vadymsidorov.yobot

import android.app.Application

class YobotApplication : Application() {
    lateinit var runtime: Runtime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = Runtime(this)
    }
}
