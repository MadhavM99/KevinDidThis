package com.megathon.incidentassistant.ui

import android.content.Context
import com.megathon.incidentassistant.R
import com.megathon.incidentassistant.session.SessionPhrases

/** The assistant's generic wording, from string resources (field wording comes from the form). */
class ResourcePhrases(private val context: Context) : SessionPhrases {

    override fun askByLabel(label: String) = str(R.string.ask_default, label)

    override fun askAgain(label: String, question: String, options: List<String>) =
        if (options.isEmpty()) str(R.string.ask_again, question)
        else str(R.string.ask_again_options, label, orList(options))

    override fun askAgainCancelHint() = str(R.string.ask_again_cancel_hint)

    override fun offerUnknown(label: String) = str(R.string.offer_unknown, label)

    override fun confirmValue(label: String, value: String) = str(R.string.confirm_value, label, value)

    override fun askOptional(labels: List<String>) = str(R.string.ask_optional, orList(labels))

    override fun askTogether(phrases: List<String>) = str(R.string.ask_together, andList(phrases))

    private fun andList(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items.single()
        2 -> str(R.string.and_two, items[0], items[1])
        else -> str(R.string.and_many, items.dropLast(1).joinToString(", "), items.last())
    }

    override fun summaryLine(label: String, value: String) = str(R.string.field_spoken, label, value)

    override fun notSpecified() = str(R.string.not_specified)

    override fun confirmReport(summary: String) = str(R.string.confirm_report, summary)

    override fun whatToChange() = str(R.string.what_to_change)

    override fun noChangeHeard() = str(R.string.no_change_heard)

    override fun didNotUnderstand() = str(R.string.did_not_understand)

    override fun didNotHear() = str(R.string.did_not_hear)

    override fun redirectToIncident() = str(R.string.redirect_to_incident)

    override fun redirectBeforeQuestion() = str(R.string.redirect_before_question)

    override fun notSureItsAnIncident() = str(R.string.not_sure_incident)

    override fun offTopicPaused() = str(R.string.off_topic_paused)

    override fun confirmCancel() = str(R.string.confirm_cancel)

    override fun cancelled() = str(R.string.cancelled)

    override fun missingBeforeSubmit(labels: List<String>) =
        str(R.string.status_missing_submit, labels.joinToString(", "))

    override fun submitted(reportId: String) = str(R.string.status_submitted, reportId)

    override fun savedNotDelivered(reportId: String) = str(R.string.status_submitted_local, reportId)

    override fun saveFailed() = str(R.string.save_failed)

    override fun alreadyFiled(reportId: String) = str(R.string.already_filed, reportId)

    override fun sessionClosed() = str(R.string.session_closed)

    override fun orList(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items.single()
        2 -> str(R.string.or_two, items[0], items[1])
        else -> str(R.string.or_many, items.dropLast(1).joinToString(", "), items.last())
    }

    private fun str(resId: Int, vararg args: Any): String = context.getString(resId, *args)
}
