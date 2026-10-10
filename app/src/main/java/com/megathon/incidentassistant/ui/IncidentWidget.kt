package com.megathon.incidentassistant.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.RemoteViews
import com.megathon.incidentassistant.MainActivity
import com.megathon.incidentassistant.R

/**
 * Home-screen widget — the Siri-style entry point.
 *
 * One tap opens the app with [MainActivity.EXTRA_AUTO_LISTEN] and recording starts
 * immediately (single-shot: VAD closes the utterance, the pipeline runs, TTS announces
 * the report). The status line mirrors what the app is doing
 * ("Listening…", "Working…", "Filed 14:32") and is pushed from the ViewModel.
 */
class IncidentWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val status = context.preferences().getString(KEY_STATUS, null)
            ?: context.getString(R.string.widget_status_ready)
        render(context, manager, ids, status)
    }

    companion object {
        private const val PREFS = "incident_widget"
        private const val KEY_STATUS = "last_status"

        /**
         * Called by the app when the assistant state changes. Saves the text so the launcher
         * can redraw the widget (rotation, re-hosting) from the last known state.
         */
        fun pushStatus(context: Context, status: String) {
            context.preferences().edit().putString(KEY_STATUS, status).apply()
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, IncidentWidget::class.java))
            if (ids.isEmpty()) return
            render(context, manager, ids, status)
        }

        private fun render(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray,
            status: String,
        ) {
            val views = RemoteViews(context.packageName, R.layout.incident_widget).apply {
                setTextViewText(R.id.widget_status, status)
                setOnClickPendingIntent(R.id.widget_root, tapIntent(context))
            }
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun tapIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_AUTO_LISTEN, true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun Context.preferences(): SharedPreferences =
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}