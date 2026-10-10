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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megathon.incidentassistant.ui.CaptureScreen
import com.megathon.incidentassistant.ui.DoneScreen
import com.megathon.incidentassistant.ui.IncidentTheme
import com.megathon.incidentassistant.ui.IncidentViewModel
import com.megathon.incidentassistant.ui.IncidentViewModelFactory
import com.megathon.incidentassistant.ui.ReviewScreen
import com.megathon.incidentassistant.ui.Step

class MainActivity : ComponentActivity() {
    private val vm: IncidentViewModel by viewModels {
        IncidentViewModelFactory(application as IncidentApp)
    }

    /**
     * Set while a widget tap is waiting for the mic-permission dialog. Compose state so
     * the root can observe it and fire the launcher from an effect (safe after resume).
     */
    private val widgetPermissionPending = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IncidentTheme {
                IncidentAssistantRoot(vm, widgetPermissionPending)
            }
        }
        if (intent?.getBooleanExtra(EXTRA_AUTO_LISTEN, false) == true) onWidgetTap()
    }

    /** Widget taps while the activity is already alive (singleTop). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO_LISTEN, false)) onWidgetTap()
    }

    /** Widget tap: start listening right away; ask for mic permission if needed. */
    private fun onWidgetTap() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            vm.requestAutoListen()
        } else {
            widgetPermissionPending.value = true
        }
    }

    companion object {
        /** Set by [com.megathon.incidentassistant.ui.IncidentWidget]: record immediately. */
        const val EXTRA_AUTO_LISTEN = "com.megathon.incidentassistant.extra.AUTO_LISTEN"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncidentAssistantRoot(vm: IncidentViewModel, widgetPermissionPending: MutableState<Boolean>) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.onMicPermissionGranted() else vm.onMicPermissionDenied()
    }

    // A widget tap that needs the permission dialog: fire it once the launcher is live.
    if (widgetPermissionPending.value) {
        LaunchedEffect(Unit) {
            widgetPermissionPending.value = false
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val onMic: () -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (state.listening || granted) vm.onMicTapped()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val stepText = stringResource(
        when (state.step) {
            Step.CAPTURE -> R.string.step_capture
            Step.REVIEW -> R.string.step_review
            Step.SUBMITTED -> R.string.step_done
        }
    )

    Scaffold(
        topBar = {
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
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Text(
                text = stepText,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            // paneTitle makes TalkBack announce the new screen when the step changes.
            Box(Modifier.semantics { paneTitle = stepText }) {
                when (state.step) {
                    Step.CAPTURE -> CaptureScreen(state, vm, onMic)
                    Step.REVIEW -> ReviewScreen(state, vm)
                    Step.SUBMITTED -> DoneScreen(state, vm)
                }
            }
        }
    }
}