package com.vadymsidorov.yobot

import android.app.Application

class YobotApplication : Application() {
    lateinit var brain: Brain
        private set

    override fun onCreate() {
        super.onCreate()
        brain = Brain()
    }
}
