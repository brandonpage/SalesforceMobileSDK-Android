package com.salesforce.androidsdk.security

import android.content.Intent
import com.salesforce.androidsdk.accounts.UserAccount
import com.salesforce.androidsdk.app.SalesforceSDKManager
import com.salesforce.androidsdk.util.EventsObservable

internal class BiometricAuthManager: LockManager(
    BIO_AUTH_POLICY, BIO_AUTH_OPT_IN, BIO_AUTH_TIMEOUT
) {
    var isLocked = true

    override fun shouldLock(): Boolean {
        val elapsedTime = System.currentTimeMillis() - lastBackgroundTimestamp
        val account = SalesforceSDKManager.getInstance().userAccountManager.currentAccount ?: return false
        val userAccount = SalesforceSDKManager.getInstance().userAccountManager.buildUserAccount(account)
        val (enabled, timeout) = getPolicy(userAccount)

        return enabled && (elapsedTime > timeout)
    }

    override fun lock() {
        isLocked = true
        val ctx = SalesforceSDKManager.getInstance().appContext
        val options = SalesforceSDKManager.getInstance().loginOptions.asBundle()
        val intent = Intent(ctx, SalesforceSDKManager.getInstance().loginActivityClass)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtras(options)
        ctx.startActivity(intent)
        EventsObservable.get().notifyEvent(EventsObservable.EventType.AppLocked)
    }

    fun biometricOptIn(account: UserAccount, optIn: Boolean = true) {
        getAccountPrefs(account, BIO_AUTH_POLICY).edit()
            .putBoolean(BIO_AUTH_OPT_IN, optIn)
            .apply()
    }

    fun isEnabled(): Boolean {
        val currentUser = SalesforceSDKManager.getInstance().userAccountManager.currentUser
        return currentUser != null && getPolicy(currentUser).first
    }

    fun shouldAllowRefresh(): Boolean {
        return !(isEnabled() && isLocked)
    }

    fun presentOptInDialog() {

    }

    companion object {
        internal const val BIO_AUTH_POLICY = "bio_auth_policy"
        internal const val BIO_AUTH_TIMEOUT = "bio_auth_timeout"
        internal const val BIO_AUTH_OPT_IN = "bio_auth_opt_in"
    }
}