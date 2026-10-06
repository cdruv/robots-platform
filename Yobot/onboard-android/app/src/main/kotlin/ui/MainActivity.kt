package com.vadymsidorov.yobot.ui

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.view.Display
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vadymsidorov.yobot.YobotApplication
import com.vadymsidorov.yobot.face.FaceRenderer

private val FaceBackground = Color(0xFF0D0712)
private val OverlayFade = tween<Float>(240)

/**
 * Full-screen face with a small corner button that toggles the debug overlay. Senses run
 * only while resumed, focused, unlocked, and on an active display.
 */
class MainActivity : ComponentActivity() {
    private val runtime get() = (application as YobotApplication).runtime

    private var resumed = false
    private var focused = false
    private var robotActive = false
    private lateinit var faceView: ComposeView
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updateForeground()
        override fun onDisplayRemoved(displayId: Int) = updateForeground()
        override fun onDisplayChanged(displayId: Int) = updateForeground()
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) setRobotActive(false)
            else updateForeground()
        }
    }

    private val requestMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            setRobotActive(false)
            updateForeground()
        } else runtime.telemetry.logger("activity").warn("RECORD_AUDIO denied; hearing stays off")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, RECEIVER_NOT_EXPORTED)
        displayManager.registerDisplayListener(displayListener, null)
        faceView = ComposeView(this)
        setContentView(faceView)
    }

    private fun showFace() {
        val runtime = runtime
        faceView.setContent {
            var showDebug by rememberSaveable { mutableStateOf(false) }
            Box(Modifier.fillMaxSize().background(FaceBackground)) {
                val target by runtime.face.state.collectAsStateWithLifecycle()
                FaceRenderer(target, motionReflex = runtime.faceMotionReflex, overlayVisible = showDebug)
                AnimatedVisibility(showDebug, enter = fadeIn(OverlayFade), exit = fadeOut(OverlayFade)) {
                    DebugOverlay(runtime, window, Modifier.fillMaxSize())
                }
                DebugToggle(
                    active = showDebug,
                    onClick = { showDebug = !showDebug },
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        focused = window.decorView.hasWindowFocus()
        updateForeground()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focused = hasFocus
        updateForeground()
    }

    override fun onPause() {
        resumed = false
        setRobotActive(false)
        super.onPause()
    }

    override fun onStop() {
        setRobotActive(false)
        super.onStop()
    }

    override fun onDestroy() {
        setRobotActive(false)
        displayManager.unregisterDisplayListener(displayListener)
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    private fun updateForeground() {
        setRobotActive(resumed && focused &&
            getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            display?.state == Display.STATE_ON)
    }

    private fun setRobotActive(active: Boolean) {
        if (active == robotActive) return
        robotActive = active
        if (active) {
            runtime.resume()
            showFace()
        } else {
            // Dispose immediately: a state change alone may wait for a frame that never
            // arrives with the screen off, leaving LaunchedEffect timers running.
            faceView.setContent {}
            faceView.disposeComposition()
            runtime.pause()
        }
    }
}
