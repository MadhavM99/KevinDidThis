package com.megathon.incidentassistant.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.megathon.incidentassistant.R
import com.megathon.incidentassistant.form.FilledFormRenderer

/**
 * The form page the report came from, filled in with everything collected, and its submit
 * button. Submitting hands the values on the page back to the app, which files the report.
 *
 * JavaScript is on only for the one script [FilledFormRenderer] adds (the page's own scripts
 * are stripped); the page can't reach the network or local files.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FilledFormScreen(state: UiState, vm: IncidentViewModel) {
    val html = state.filledFormHtml ?: return
    val bridge = remember(vm) {
        object {
            @JavascriptInterface
            fun submit(json: String) = vm.onSubmitFilledForm(json)
        }
    }
    Column(Modifier.fillMaxSize()) {
        TextButton(
            onClick = vm::onBackFromForm,
            enabled = !state.processing,
            modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.form_back)) }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.blockNetworkLoads = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    addJavascriptInterface(bridge, FilledFormRenderer.BRIDGE)
                }
            },
            update = { web ->
                if (web.tag != html) {
                    web.tag = html
                    web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            },
        )
    }
}
