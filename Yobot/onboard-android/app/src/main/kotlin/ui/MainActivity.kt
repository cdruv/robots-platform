package com.vadymsidorov.yobot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import com.vadymsidorov.yobot.Brain
import com.vadymsidorov.yobot.YobotApplication

/** Fullscreen, always-on face. Senses run between onStart and onStop. */
class MainActivity : ComponentActivity() {
    private val brain: Brain get() = (application as YobotApplication).brain

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        brain.telemetry.logger("ui").info("permissions: $results")
        if (results.values.any { it } && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            brain.restart(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            FaceScreen(
                faceState = brain.face.state,
                glance = brain.face.glance,
                onTouch = brain.touchSense::post,
                overlay = {},
            )
        }
        val missing = REQUIRED_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())
    }

    override fun onStart() {
        super.onStart()
        brain.start(lifecycleOwner = this)
    }

    override fun onStop() {
        brain.stop()
        super.onStop()
    }

    private companion object {
        val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
    }
}
