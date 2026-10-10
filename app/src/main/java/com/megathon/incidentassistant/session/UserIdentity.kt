package com.megathon.incidentassistant.session

/**
 * Who is using the app, from a source the app trusts (device management / sign-in) —
 * never inferred from speech. [source] is recorded as the field's evidence.
 */
data class UserIdentity(val displayName: String, val source: String)

/** Returns the current user, or null when the app has no trustworthy identity. */
fun interface IdentityProvider {
    fun currentUser(): UserIdentity?
}

/** The `prefill` value that maps a form field to [UserIdentity.displayName]. */
const val PREFILL_REPORTER = "reporter"
