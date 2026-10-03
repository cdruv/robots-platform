package com.vadymsidorov.yobot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
 * while the activity is started; the microphone permission is requested on first start.
 */
class MainActivity : ComponentActivity() {
    private val brain get() = (application as YobotApplication).brain

    private val requestMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) brain.startSenses()
        else brain.telemetry.logger("activity").warn("RECORD_AUDIO denied; hearing stays off")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val brain = brain
        setContent {
            val target by brain.face.state.collectAsStateWithLifecycle()
            var showDebug by rememberSaveable { mutableStateOf(false) }
            Box(Modifier.fillMaxSize().background(FaceBackground)) {
                FaceRenderer(target, motionReflex = brain.faceMotionReflex, overlayVisible = showDebug)
                AnimatedVisibility(showDebug, enter = fadeIn(OverlayFade), exit = fadeOut(OverlayFade)) {
                    DebugOverlay(brain, Modifier.fillMaxSize())
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
        // Senses that need no permission start now; hearing joins once the microphone is granted.
        brain.startSenses()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        brain.stopSenses()
        super.onStop()
    }
}
