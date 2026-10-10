package com.megathon.incidentassistant.data

import android.content.Context
import com.megathon.incidentassistant.session.IdentityProvider
import com.megathon.incidentassistant.session.UserIdentity

/** The user's own details, entered once on first launch (editable later). */
data class UserProfile(val name: String, val email: String = "", val phone: String = "")

/** Keeps [UserProfile] on the device only (app-private SharedPreferences). */
class LocalProfileStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): UserProfile? {
        val name = prefs.getString(KEY_NAME, null)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return UserProfile(
            name = name,
            email = prefs.getString(KEY_EMAIL, null).orEmpty(),
            phone = prefs.getString(KEY_PHONE, null).orEmpty(),
        )
    }

    fun save(profile: UserProfile) {
        prefs.edit()
            .putString(KEY_NAME, profile.name.trim())
            .putString(KEY_EMAIL, profile.email.trim())
            .putString(KEY_PHONE, profile.phone.trim())
            .apply()
    }

    private companion object {
        const val PREFS = "user_profile"
        const val KEY_NAME = "name"
        const val KEY_EMAIL = "email"
        const val KEY_PHONE = "phone"
    }
}

/**
 * The reporter: the profile the user entered on this device, else the one provisioned by
 * device management ([fallback]). Null only before the first-launch profile is saved.
 */
class ProfileIdentityProvider(
    private val store: LocalProfileStore,
    private val fallback: IdentityProvider,
) : IdentityProvider {
    override fun currentUser(): UserIdentity? =
        store.load()?.let {
            UserIdentity(
                displayName = it.name,
                source = "app profile",
                email = it.email.takeIf(String::isNotBlank),
                phone = it.phone.takeIf(String::isNotBlank),
            )
        } ?: fallback.currentUser()
}
