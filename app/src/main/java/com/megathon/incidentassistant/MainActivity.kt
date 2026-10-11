package com.megathon.incidentassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megathon.incidentassistant.ui.CaptureScreen
import com.megathon.incidentassistant.ui.DoneScreen
import com.megathon.incidentassistant.ui.FilledFormScreen
import com.megathon.incidentassistant.ui.IncidentTheme
import com.megathon.incidentassistant.ui.IncidentViewModel
import com.megathon.incidentassistant.ui.IncidentViewModelFactory
import com.megathon.incidentassistant.ui.ProfileScreen
import com.megathon.incidentassistant.ui.ReviewScreen
import com.megathon.incidentassistant.ui.Step

class MainActivity : ComponentActivity() {
    private val vm: IncidentViewModel by viewModels {
        IncidentViewModelFactory(application as IncidentApp)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IncidentTheme {
                IncidentAssistantRoot(vm)
            }
        }
        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent)
    }

    /** Widget tap → start listening as soon as the form is ready (no extra tap). */
    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.action == ACTION_AUTO_LISTEN) vm.onWidgetLaunch()
    }

    companion object {
        const val ACTION_AUTO_LISTEN = "com.megathon.incidentassistant.action.AUTO_LISTEN"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncidentAssistantRoot(vm: IncidentViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.onMicPermissionGranted() else vm.onMicPermissionDenied()
    }

    // The recorder flow is button-driven: Start → speak → Stop → review → Continue.
    // The microphone permission is only needed for Start.
    val onStartRecording: () -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.onStartRecording()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // The capture step is the immersive assistant stage (no chrome);
    // Review / Submitted keep the usual app bar + step header.
    val immersive = state.step == Step.CAPTURE && !state.editingProfile

    Scaffold(
        containerColor = if (immersive) Color.Transparent else MaterialTheme.colorScheme.surface,
        topBar = {
            if (!immersive) {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.app_name),
                            modifier = Modifier.semantics { heading() },
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.editingProfile) {
                // First launch (or "Edit"): the user's own details, before any report.
                ProfileScreen(state, vm)
            } else if (immersive) {
                CaptureScreen(state, vm, onStartRecording)
            } else {
                val stepText = stringResource(
                    when (state.step) {
                        Step.REVIEW -> R.string.step_review
                        Step.FORM -> R.string.step_form
                        Step.SUBMITTED -> R.string.step_done
                        Step.CAPTURE -> R.string.step_capture
                    }
                )
                // paneTitle makes TalkBack announce the new screen when the step changes.
                Box(Modifier.fillMaxSize().semantics { paneTitle = stepText }) {
                    when (state.step) {
                        Step.REVIEW -> ReviewScreen(state, vm, onBack = vm::onAddMore)
                        Step.FORM -> FilledFormScreen(state, vm)
                        Step.SUBMITTED -> DoneScreen(state, vm)
                        Step.CAPTURE -> Unit // handled by the immersive branch
                    }
                }
            }
        }
    }
}