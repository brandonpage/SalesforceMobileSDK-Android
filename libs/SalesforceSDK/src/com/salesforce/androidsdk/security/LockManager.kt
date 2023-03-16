package com.salesforce.androidsdk.security

import android.content.Context
import android.content.SharedPreferences
import com.salesforce.androidsdk.accounts.UserAccount
import com.salesforce.androidsdk.app.SalesforceSDKManager

typealias Policy = Pair<Boolean, Int>

internal abstract class LockManager(
    private val policyKey: String,
    private val enabledKey: String,
    private val timeoutKey: String,
) {
    abstract fun shouldLock(): Boolean
    abstract fun lock()

    var lastBackgroundTimestamp: Long = 0

    fun onAppForegrounded() {
        if (shouldLock()) {
            lock()
        }
    }

    fun onAppBackgrounded() {
        lastBackgroundTimestamp = System.currentTimeMillis()
    }

    fun getAccountPrefs(account: UserAccount, key: String): SharedPreferences {
        val ctx = SalesforceSDKManager.getInstance().appContext
        return ctx.getSharedPreferences(key + account.userLevelFilenameSuffix, Context.MODE_PRIVATE)
    }

    fun getGlobalPrefs(key: String): SharedPreferences {
        val ctx = SalesforceSDKManager.getInstance().appContext
        return ctx.getSharedPreferences(key, Context.MODE_PRIVATE)
    }

    fun getPolicy(account: UserAccount): Policy {
        val accountPolicy = getAccountPrefs(account, policyKey)
        return accountPolicy.getBoolean(enabledKey, false) to accountPolicy.getInt(timeoutKey, 0)
    }

    fun getGlobalPolicy(): Policy {
        val globalPolicy = getGlobalPrefs(policyKey)
        return globalPolicy.getBoolean(enabledKey, false) to globalPolicy.getInt(timeoutKey, 0)
    }

    open fun storePolicy(account: UserAccount, enabled: Boolean, timeout: Int) {
        getAccountPrefs(account, policyKey).edit()
            .putBoolean(enabledKey, enabled)
            .putInt(timeoutKey, timeout)
            .apply()
    }

    open fun cleanUp(account: UserAccount) {
        getAccountPrefs(account, policyKey).edit()
            .remove(enabledKey)
            .remove(timeoutKey)
            .apply()
    }
}