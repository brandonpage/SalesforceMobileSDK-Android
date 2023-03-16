package com.salesforce.androidsdk.security

import android.content.Intent
import com.salesforce.androidsdk.accounts.UserAccount
import com.salesforce.androidsdk.app.SalesforceSDKManager
import com.salesforce.androidsdk.ui.ScreenLockActivity
import com.salesforce.androidsdk.util.EventsObservable

internal class ScreenLockManager: LockManager(
    MOBILE_POLICY_PREF, SCREEN_LOCK_ENABLED, SCREEN_LOCK_TIMEOUT
) {

    override fun storePolicy(account: UserAccount, required: Boolean, timeout: Int) {
        super.storePolicy(account, required, timeout)

        if (required) {
            val globalPrefs = getGlobalPrefs(MOBILE_POLICY_PREF)
            val currentTimeout = globalPrefs.getInt(SCREEN_LOCK_TIMEOUT, 0)
            val globalPrefsEditor = globalPrefs.edit()
            globalPrefsEditor.putBoolean(SCREEN_LOCK_ENABLED, true)
            if (currentTimeout == 0 || timeout < currentTimeout) {
                globalPrefsEditor.putInt(SCREEN_LOCK_TIMEOUT, timeout)
            }
            globalPrefsEditor.apply()
            lock()
        }
    }

    override fun shouldLock(): Boolean {
        val elapsedTime = System.currentTimeMillis() - lastBackgroundTimestamp
        val (hasLock, timeout) = getGlobalPolicy()

        return hasLock && (elapsedTime > timeout)
    }

    override fun lock() {
        val ctx = SalesforceSDKManager.getInstance().appContext
        val intent = Intent(ctx, ScreenLockActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        EventsObservable.get().notifyEvent(EventsObservable.EventType.AppLocked)
    }

    override fun cleanUp(account: UserAccount) {
        // Clean up and remove lock for account.
        super.cleanUp(account)

        // Determine if any other users still need Screen Lock.
        val accounts = SalesforceSDKManager.getInstance()
            .userAccountManager.authenticatedUsers
        var lowestTimeout = Int.MAX_VALUE

        accounts.remove(account)
        if (!accounts.isNullOrEmpty()) {
            accounts.forEach { remainingAccount ->
                if (remainingAccount != null) {
                    val accountPrefs = getAccountPrefs(remainingAccount, MOBILE_POLICY_PREF)
                    if (accountPrefs.getBoolean(SCREEN_LOCK_ENABLED, false)) {
                        val timeout = accountPrefs.getInt(SCREEN_LOCK_TIMEOUT, Int.MAX_VALUE)
                        if (timeout < lowestTimeout) {
                            lowestTimeout = timeout
                        }
                    }
                }
            }
            if (lowestTimeout < Int.MAX_VALUE) {
                getGlobalPrefs(MOBILE_POLICY_PREF).edit()
                    .putInt(SCREEN_LOCK_TIMEOUT, lowestTimeout)
                    .apply()
                return
            }
        }

        // If we have not returned, no other accounts require Screen Lock.
        reset()
    }

    fun reset() {
        getGlobalPrefs(MOBILE_POLICY_PREF).edit()
            .remove(SCREEN_LOCK_ENABLED)
            .remove(SCREEN_LOCK_TIMEOUT)
            .apply()
    }

    companion object {
        const val MOBILE_POLICY_PREF = "mobile_policy"
        const val SCREEN_LOCK_ENABLED = "screen_lock"
        const val SCREEN_LOCK_TIMEOUT = "screen_lock_timeout"
    }
}