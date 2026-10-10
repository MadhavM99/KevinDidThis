package com.megathon.incidentassistant.session

/**
 * Who is using the app, from a source the app trusts (the profile the user entered on first
 * launch, or device management) — never inferred from speech. [source] is recorded as the
 * field's evidence.
 */
data class UserIdentity(
    val displayName: String,
    val source: String,
    val email: String? = null,
    val phone: String? = null,
) {
    /** The value for a field whose `prefill` is [kind], or null when the user has none. */
    fun valueFor(kind: String): String? = when (kind) {
        PREFILL_REPORTER -> displayName
        PREFILL_EMAIL -> email
        PREFILL_PHONE -> phone
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }
}

/** Returns the current user, or null when the app has no trustworthy identity. */
fun interface IdentityProvider {
    fun currentUser(): UserIdentity?
}

/**
 * `prefill` kinds: a field holding the reporting user's own name, email or phone. Such a
 * field is filled from [UserIdentity] and never asked in the conversation — people answer
 * "who is reporting?" with "me", not their name.
 */
const val PREFILL_REPORTER = "reporter"
const val PREFILL_EMAIL = "reporter_email"
const val PREFILL_PHONE = "reporter_phone"

val PREFILL_KINDS = listOf(PREFILL_REPORTER, PREFILL_EMAIL, PREFILL_PHONE)
