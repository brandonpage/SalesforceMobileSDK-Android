package com.salesforce.androidsdk.security

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.salesforce.androidsdk.accounts.UserAccount
import com.salesforce.androidsdk.app.SalesforceSDKManager
import com.salesforce.androidsdk.rest.RestClient
import com.salesforce.androidsdk.rest.RestClient.AsyncRequestCallback
import com.salesforce.androidsdk.rest.RestRequest
import com.salesforce.androidsdk.rest.RestResponse
import com.salesforce.androidsdk.util.EventsObservable
import java.net.HttpURLConnection

class BioAuthManager {

    fun storePolicy(account: UserAccount, enabled: Boolean, timeout: Int) {
        val ctx = SalesforceSDKManager.getInstance().appContext
        val accountSharedPrefs = ctx.getSharedPreferences(
            BIO_AUTH_POLICY + account.userLevelFilenameSuffix,
            Context.MODE_PRIVATE,
        )
        accountSharedPrefs.edit()
            .putBoolean(BIO_AUTH_POLICY, enabled)
            .putInt(BIO_AUTH_TIMEOUT, timeout)
            .apply()
    }

    fun optIn(account: UserAccount, optIn: Boolean) {
        val ctx = SalesforceSDKManager.getInstance().appContext
        val accountSharedPrefs = ctx.getSharedPreferences(
            BIO_AUTH_POLICY + account.userLevelFilenameSuffix,
            Context.MODE_PRIVATE,
        )
        accountSharedPrefs.edit().putBoolean(BIO_AUTH_OPT_IN, optIn).apply()
    }


    fun onAppForeground() {
//        if (shouldLock()) {
//            lock()
//        }

        var client: RestClient? = null
        try {
            client = SalesforceSDKManager.getInstance().clientManager.peekRestClient()
        } catch (e: Exception) {
            // nothing
        }

//
//        if (client != null) {
//            client.sendAsync(RestRequest.getRequestForUserInfo(), object : AsyncRequestCallback {
//                override fun onSuccess(request: RestRequest, response: RestResponse) {
//                    Log.i("bpage", "reponse: $response")
//                }
//
//                override fun onError(exception: Exception) {
//                    Log.i("bpage", "error, exception: " + exception.localizedMessage)
//                }
//            })

            if (SalesforceSDKManager.getInstance().isBioAuthEnabled) {
                if (client != null) {
                    client.sendAsync(RestRequest.getRequestForUserInfo(), object : AsyncRequestCallback {
                        override fun onSuccess(request: RestRequest, response: RestResponse) {
                            Log.i("bpage", "reponse: $response")
                            val responseCode = response.statusCode
                            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED ||
                                responseCode == HttpURLConnection.HTTP_FORBIDDEN
                            ) {
                                lock()
                            }
                        }

                        override fun onError(exception: Exception) {
                            Log.i("bpage", "error, exception: " + exception.localizedMessage)
                        }
                    })
                }
            }
    }

    // call on user switch?
    fun onAppBackground() {
        val accountManager = SalesforceSDKManager.getInstance().userAccountManager
        val userAccount = accountManager.buildUserAccount(accountManager.currentAccount)
//        val lastRestActivity = SalesforceSDKManager.getInstance().clientManager.peekRestClient()?.lastActivity

        // set last rest activity in secure prefs
    }

    fun shouldAllowRefresh(): Boolean {
//        return false

        return !SalesforceSDKManager.getInstance().isBioAuthEnabled

        if (!SalesforceSDKManager.getInstance().isBioAuthEnabled) {
            return true
        }

        // check if the app has backgrounded?


        // This foreground check might not work on fork os's
        val appProcessInfo =  ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(appProcessInfo)
        return (appProcessInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                appProcessInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE)
    }

    private fun shouldLock(): Boolean {
        val account = SalesforceSDKManager.getInstance().userAccountManager.currentAccount ?: return false
        val userAccount = SalesforceSDKManager.getInstance().userAccountManager.buildUserAccount(account)
        val ctx = SalesforceSDKManager.getInstance().appContext
        val accountSharedPrefs = ctx.getSharedPreferences(
            BIO_AUTH_POLICY + userAccount.userLevelFilenameSuffix,
            Context.MODE_PRIVATE,
        )
        val timeout = accountSharedPrefs.getInt(BIO_AUTH_TIMEOUT, -1)

        if (timeout <= 0) {
            return false
        }

        /**
         * "It is important to note that a current active session is not updated until halfway through the session's
         * timeout period. For example, a session with a 30-minute timeout value does not begin to check  for activity
         * until the last 15 minutes of the session. Regardless of activity during the first half of the session,
         * if no activity is detected in the latter half, the session will time out."
         *
         * source: https://help.salesforce.com/s/articleView?id=000383251&type=1
         */
        val sessionHalftime = timeout / 2
        val lastRestCall = lastRestCall()

        Log.i("bpage", "lastActivity: $lastRestCall, diff: ${System.currentTimeMillis() - lastRestCall}")

        return (System.currentTimeMillis() - lastRestCall) > sessionHalftime
    }

    fun lock() {
        // storeCurrentUserInfo here?

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

    private fun lastRestCall(): Long {
        return SalesforceSDKManager.getInstance().clientManager.peekRestClient().lastActivity ?: 0
    }

    fun onSuccess() {

    }

    companion object {
        internal const val BIO_AUTH_POLICY = "bio_auth_policy"
        internal const val BIO_AUTH_TIMEOUT = "bio_auth_timeout"
        internal const val BIO_AUTH_OPT_IN = "bio_auth_opt_in"
    }
}