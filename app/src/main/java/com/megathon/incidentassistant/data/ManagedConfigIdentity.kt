package com.megathon.incidentassistant.data

import android.content.Context
import android.content.RestrictionsManager
import com.megathon.incidentassistant.session.IdentityProvider
import com.megathon.incidentassistant.session.UserIdentity

/**
 * The signed-in user as provisioned by the organisation's device management (Android
 * managed configuration, keys declared in `res/xml/app_restrictions.xml`). The user
 * cannot set it themselves, which is what makes it trustworthy enough to file a report
 * under. Not provisioned → null, and the form asks for the reporter as before.
 *
 * A future sign-in / user-profile integration implements [IdentityProvider] the same way.
 */
class ManagedConfigIdentityProvider(context: Context) : IdentityProvider {

    private val restrictions =
        context.applicationContext.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager

    override fun currentUser(): UserIdentity? {
        val name = runCatching { restrictions.applicationRestrictions.getString(KEY_REPORTER_NAME) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return UserIdentity(displayName = name, source = "device management profile")
    }

    private companion object {
        const val KEY_REPORTER_NAME = "reporter_name"
    }
}
