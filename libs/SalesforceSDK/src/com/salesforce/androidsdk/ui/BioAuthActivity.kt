package com.salesforce.androidsdk.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.WebSettings.LayoutAlgorithm
import android.webkit.WebView
import android.widget.Button
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricPrompt.PromptInfo
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.salesforce.androidsdk.R
import com.salesforce.androidsdk.accounts.UserAccount
import com.salesforce.androidsdk.accounts.UserAccountManager
import com.salesforce.androidsdk.app.SalesforceSDKManager
import com.salesforce.androidsdk.auth.idp.IDPAccountPickerActivity
import com.salesforce.androidsdk.auth.idp.IDPInititatedLoginReceiver
import com.salesforce.androidsdk.rest.ClientManager
import com.salesforce.androidsdk.ui.LoginActivity.ChangeServerReceiver
import com.salesforce.androidsdk.ui.LoginActivity.SPAuthCallback
import com.salesforce.androidsdk.ui.OAuthWebviewHelper.OAuthWebviewHelperEvents
import com.salesforce.androidsdk.util.AuthConfigTask
import com.salesforce.androidsdk.util.EventsObservable
import com.salesforce.androidsdk.util.SalesforceSDKLogger

class BioAuthActivity: FragmentActivity(), OAuthWebviewHelperEvents {
    private var webviewHelper: OAuthWebviewHelper? = null
    private var changeServerReceiver: ChangeServerReceiver? = null
    private var receiverRegistered = false
    private var authCallback: SPAuthCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isDarkTheme = SalesforceSDKManager.getInstance().isDarkTheme
        setTheme(if (isDarkTheme) R.style.SalesforceSDK_Dark_Login else R.style.SalesforceSDK)
        SalesforceSDKManager.getInstance().setViewNavigationVisibility(this)

        // Getting login options from intent's extras.
        val loginOptions = ClientManager.LoginOptions.fromBundle(intent.extras)

        // Protect against screenshots.
//        window.setFlags(
//            WindowManager.LayoutParams.FLAG_SECURE,
//            WindowManager.LayoutParams.FLAG_SECURE
//        )

        // Fetches auth config if required.
        try {
            AuthConfigTask(null).execute().get()
        } catch (e: Exception) {
            SalesforceSDKLogger.e("bpage", "Exception occurred while fetching auth config", e)
        }

        // Setup content view.
        setContentView(R.layout.sf__login)
        if (SalesforceSDKManager.getInstance().isIDPLoginFlowEnabled) {
            val button = findViewById<Button>(R.id.sf__idp_login_button)
            button.visibility = View.VISIBLE
        }

        if (SalesforceSDKManager.getInstance().isBioAuthEnabled) {
            val button = findViewById<Button>(R.id.sf__bio_login_button)
            button.visibility = View.VISIBLE

        }

        // Setup the WebView.
        val webView = findViewById<WebView>(R.id.sf__oauth_webview)
        val webSettings = webView.settings
        webSettings.useWideViewPort = true
        webSettings.layoutAlgorithm = LayoutAlgorithm.NORMAL
        webSettings.javaScriptEnabled = true
        webSettings.allowFileAccessFromFileURLs = true
        webSettings.javaScriptCanOpenWindowsAutomatically = true
        webSettings.databaseEnabled = true
        webSettings.domStorageEnabled = true
        EventsObservable.get().notifyEvent(EventsObservable.EventType.AuthWebViewCreateComplete, webView)
        webviewHelper = OAuthWebviewHelper(this,this, loginOptions, webView, savedInstanceState)

        // Let observers know
        EventsObservable.get().notifyEvent(EventsObservable.EventType.LoginActivityCreateComplete, this)
//        certAuthOrLogin()
//        if (!receiverRegistered) {
//            changeServerReceiver = ChangeServerReceiver()
//            val changeServerFilter = IntentFilter(ServerPickerActivity.CHANGE_SERVER_INTENT)
//            registerReceiver(changeServerReceiver, changeServerFilter)
//            receiverRegistered = true
//        }
//        authCallback = SPAuthCallback()

        webviewHelper!!.loadLoginPage()

        presentAuth()
    }

    fun onBioAuthClick(v: View) {
        presentAuth()
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(changeServerReceiver)
            receiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        // If this is a callback from Chrome, processes it and does nothing else.
//        if (isChromeCallback(intent)) {
//            completeAuthFlow(intent)
//            return
//        }

        // Reloads login page for every new intent to ensure the correct login server is selected.
//        if (webviewHelper!!.shouldReloadPage()) {
            webviewHelper!!.loadLoginPage()
//        }
//
        // Launches IDP login flow directly for IDP initiated login flow.
//        if (intent != null) {
//            val extras = intent.extras
//            if (extras != null) {
//                userHint = extras.getString(IDPInititatedLoginReceiver.USER_HINT_KEY)
//                spActivityName = extras.getString(IDPInititatedLoginReceiver.SP_ACTVITY_NAME_KEY)
//                spActivityExtras = extras.getBundle(IDPInititatedLoginReceiver.SP_ACTVITY_EXTRAS_KEY)
//                val isIdpInitFlow = extras.getBoolean(IDPInititatedLoginReceiver.IDP_INIT_LOGIN_KEY)
//                if (isIdpInitFlow) {
//                    onIDPLoginClick(null)
//                }
//            }
//        }
    }

//    class ChangeServerReceiver : BroadcastReceiver() {
//        override fun onReceive(context: Context, intent: Intent) {
//            if (intent.action != null) {
//                val action = intent.action
//                if (ServerPickerActivity.CHANGE_SERVER_INTENT == action) {
//                    webviewHelper.loadLoginPage()
//                }
//            }
//        }
//    }

    private fun presentAuth() {
        val biometricPrompt: BiometricPrompt = getBiometricPrompt()
        val biometricManager = BiometricManager.from(this)
        when (biometricManager.canAuthenticate(getAuthenticators())) {
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                // This should never happen.
                val error = getString(R.string.sf__screen_lock_error)
                SalesforceSDKLogger.e("bpage", "Biometric manager cannot authenticate. $error")
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {

                /*
                 * Prompts the user to setup OS screen lock and biometric.
                 * TODO: Remove when min API > 29.
                 */if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val biometricIntent = Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                    biometricIntent.putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, getAuthenticators())
//                    actionButton.setOnClickListener(View.OnClickListener { v: View? ->
//                        startActivityForResult(
//                            biometricIntent,
//                            ScreenLockActivity.SETUP_REQUEST_CODE
//                        )
//                    })
                }
//                else {
//                    val lockScreenIntent = Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD)
//                    actionButton.setOnClickListener(View.OnClickListener { v: View? ->
//                        startActivityForResult(
//                            lockScreenIntent,
//                            ScreenLockActivity.SETUP_REQUEST_CODE
//                        )
//                    })
//                }
            }

            BiometricManager.BIOMETRIC_SUCCESS -> {
                biometricPrompt.authenticate(getPromptInfo())
            }
        }
    }

    private fun getPromptInfo(): PromptInfo {
        return PromptInfo.Builder()
            .setTitle("Login with Biometric")
            .setSubtitle("Subtitle")
            .setAllowedAuthenticators(getAuthenticators())
            .setConfirmationRequired(false)
            .build()
    }

    private fun getBiometricPrompt(): BiometricPrompt {
        return BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    finishSuccess()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
            })
    }

    private fun finishSuccess() {
        // TODO: refresh token
//        sendAccessibilityEvent(getString(R.string.sf__screen_lock_auth_success))
        finish()
    }

    private fun getAuthenticators(): Int {
        // TODO: Remove when min API > 29.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL else BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }

    override fun loadingLoginPage(loginUrl: String?) {
        Log.i("bpage", "onAccountAuthenticatorResult")
        actionBar?.title = loginUrl
    }

    override fun onAccountAuthenticatorResult(authResult: Bundle?) {
        Log.i("bpage", "onAccountAuthenticatorResult")
    }

    override fun finish(userAccount: UserAccount?) {
//        initAnalyticsManager(userAccount)
        val userAccountManager = SalesforceSDKManager.getInstance().userAccountManager
        val authenticatedUsers = userAccountManager.authenticatedUsers
        val numAuthenticatedUsers = authenticatedUsers?.size ?: 0

        /*
         * Sends user switch intents only if this login flow is not a login triggered due
         * to an incoming authentication request from an SP app or first user to login on IDP.
         * If it is an incoming SP request, we should add the user account but NOT switch to
         * the user or send user switch intents unless it's the first user being logged in.
         */

        /*
         * Sends user switch intents only if this login flow is not a login triggered due
         * to an incoming authentication request from an SP app or first user to login on IDP.
         * If it is an incoming SP request, we should add the user account but NOT switch to
         * the user or send user switch intents unless it's the first user being logged in.
         */
        val isFirstUserOrNotIDPFlow =
            !SalesforceSDKManager.getInstance().isIDPAppLoginFlowActive || numAuthenticatedUsers <= 1
        if (isFirstUserOrNotIDPFlow) {
            val userSwitchType: Int = if (numAuthenticatedUsers == 1) {

                // We've already authenticated the first user, so there should be one.
                UserAccountManager.USER_SWITCH_TYPE_FIRST_LOGIN
            } else if (numAuthenticatedUsers > 1) {

                // Otherwise we're logging in with an additional user.
                UserAccountManager.USER_SWITCH_TYPE_LOGIN
            } else {

                // This should never happen but if it does, pass in the "unknown" value.
                UserAccountManager.USER_SWITCH_TYPE_DEFAULT
            }
            userAccountManager.sendUserSwitchIntent(userSwitchType, null)
        }

        /*
         * Passes back the added user account object if this is a login flow in the IDP app
         * initiated by an incoming request for authentication from an SP app.
         */
        if (userAccount != null && SalesforceSDKManager.getInstance().isIDPAppLoginFlowActive) {
            val intent = Intent(IDPAccountPickerActivity.IDP_LOGIN_COMPLETE_ACTION)
            intent.putExtra(IDPAccountPickerActivity.USER_ACCOUNT_KEY, userAccount.toBundle())
            sendBroadcast(intent)
        }

//        // If the IDP app specified a component to launch after login, launches that component.
//        if (!TextUtils.isEmpty(spActivityName)) {
//            try {
//                val intent = Intent(this, Class.forName(spActivityName))
//                intent.addCategory(Intent.CATEGORY_DEFAULT)
//                intent.putExtra(IDPInititatedLoginReceiver.SP_ACTVITY_EXTRAS_KEY, spActivityExtras)
//                startActivity(intent)
//            } catch (e: java.lang.Exception) {
//                SalesforceSDKLogger.e(LoginActivity.TAG, "Could not start activity", e)
//            }
//        }
//
//
//        // Cleans up some state before dismissing activity.
//        userHint = null
//        spActivityName = null
//        spActivityExtras = null
        finish()
    }

}