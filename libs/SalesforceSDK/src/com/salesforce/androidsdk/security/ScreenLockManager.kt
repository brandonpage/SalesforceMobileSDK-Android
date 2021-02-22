package com.salesforce.androidsdk.security

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.salesforce.androidsdk.accounts.UserAccount
import com.salesforce.androidsdk.app.SalesforceSDKManager
import com.salesforce.androidsdk.util.EventsObservable

class ScreenLockManager(ctx: Context) {

    // Private preference where we stored the org settings.
    private val MOBILE_POLICY_PREF = "mobile_policy"
    private val SCREEN_LOCK = "screen_lock"

    private var mobilePolicy = readMobilePolicy()
    private var shouldLock = true

    fun storeMobilePolicyForOrg(account: UserAccount, screenLockRequired: Boolean) {
        val ctx = SalesforceSDKManager.getInstance().appContext as Context
        val accountSharedPrefs = ctx.getSharedPreferences(MOBILE_POLICY_PREF
                + account.orgLevelFilenameSuffix, Context.MODE_PRIVATE)
        with(accountSharedPrefs.edit()) {
            putBoolean(SCREEN_LOCK, screenLockRequired)
            apply()
        }

        val globalSharedPrefs = ctx.getSharedPreferences(MOBILE_POLICY_PREF, Context.MODE_PRIVATE)
        if (screenLockRequired && !globalSharedPrefs.getBoolean(SCREEN_LOCK, false)) {
            with(globalSharedPrefs.edit()) {
                putBoolean(SCREEN_LOCK, true)
                apply()
            }
            // This is necessary to lock the app upon initial login
            mobilePolicy = true
        }
    }

    fun onResume(): Boolean {
        val locked = mobilePolicy && shouldLock
        if (locked) {
            val ctx = SalesforceSDKManager.getInstance().appContext
            val intent = Intent(ctx, SalesforceSDKManager.getInstance().screenLockActivity)
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (ctx is Activity) {
                ctx.startActivityForResult(intent, PasscodeManager.PASSCODE_REQUEST_CODE)
            } else {
                ctx.startActivity(intent)
            }
            EventsObservable.get().notifyEvent(EventsObservable.EventType.AppLocked)
        }

        // If locked, do nothing - when the app gets unlocked we will be back here.
        return !locked
    }

    fun onPause() {
        setShouldLock(true)
    }

    fun setShouldLock(shouldLock: Boolean) {
        this.shouldLock = shouldLock
    }

    fun shouldLock(): Boolean {
        return this.shouldLock
    }

    fun reset() {
        val ctx = SalesforceSDKManager.getInstance().appContext
        val globalSharedPrefs = ctx.getSharedPreferences(MOBILE_POLICY_PREF, Context.MODE_PRIVATE)
        with(globalSharedPrefs.edit()) {
            remove(SCREEN_LOCK)
            apply()
        }
    }

    fun cleanUp(account: UserAccount) {
        val ctx = SalesforceSDKManager.getInstance().appContext as Context
        val accountSharedPrefs = ctx.getSharedPreferences(MOBILE_POLICY_PREF
                + account.orgLevelFilenameSuffix, Context.MODE_PRIVATE)
        with(accountSharedPrefs.edit()) {
            remove(SCREEN_LOCK)
            apply()
        }

        // TODO: Get remaining accounts and determine is Screen Lock is still needed.
    }

    private fun readMobilePolicy(): Boolean {
        val ctx = SalesforceSDKManager.getInstance().appContext
        val sharedPrefs = ctx.getSharedPreferences(MOBILE_POLICY_PREF, Context.MODE_PRIVATE)
        return sharedPrefs.getBoolean(SCREEN_LOCK, false)
    }
}