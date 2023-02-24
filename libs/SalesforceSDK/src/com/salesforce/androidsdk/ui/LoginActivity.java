/*
 * Copyright (c) 2011-present, salesforce.com, inc.
 * All rights reserved.
 * Redistribution and use of this software in source and binary forms, with or
 * without modification, are permitted provided that the following conditions
 * are met:
 * - Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * - Neither the name of salesforce.com, inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission of salesforce.com, inc.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.salesforce.androidsdk.ui;

import static androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG;
import static androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK;
import static androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.AccountAuthenticatorActivity;
import android.app.ActionBar;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.tv.DsmccRequest;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.security.KeyChain;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebSettings.LayoutAlgorithm;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.salesforce.androidsdk.R;
import com.salesforce.androidsdk.accounts.UserAccount;
import com.salesforce.androidsdk.accounts.UserAccountManager;
import com.salesforce.androidsdk.analytics.SalesforceAnalyticsManager;
import com.salesforce.androidsdk.app.SalesforceSDKManager;
import com.salesforce.androidsdk.auth.OAuth2;
import com.salesforce.androidsdk.auth.idp.IDPAccountPickerActivity;
import com.salesforce.androidsdk.auth.idp.IDPInititatedLoginReceiver;
import com.salesforce.androidsdk.auth.idp.SPRequestHandler;
import com.salesforce.androidsdk.config.RuntimeConfig;
import com.salesforce.androidsdk.config.RuntimeConfig.ConfigKey;
import com.salesforce.androidsdk.rest.ClientManager;
import com.salesforce.androidsdk.rest.ClientManager.LoginOptions;
import com.salesforce.androidsdk.rest.RestClient;
import com.salesforce.androidsdk.ui.OAuthWebviewHelper.OAuthWebviewHelperEvents;
import com.salesforce.androidsdk.util.AuthConfigTask;
import com.salesforce.androidsdk.util.EventsObservable;
import com.salesforce.androidsdk.util.EventsObservable.EventType;
import com.salesforce.androidsdk.util.SalesforceSDKLogger;
import com.salesforce.androidsdk.util.UriFragmentParser;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Login Activity: takes care of authenticating the user.
 * Authorization happens inside a web view. Once we get our authorization code,
 * we swap it for an access and refresh token a create an account through the
 * account manager to store them.
 *
 * The bulk of the work for this is actually managed by OAuthWebviewHelper class.
 */
//public class LoginActivity extends FragmentActivity implements OAuthWebviewHelperEvents {
public class LoginActivity extends FragmentActivity implements OAuthWebviewHelperEvents {

    public static final int PICK_SERVER_REQUEST_CODE = 10;
    private static final String TAG = "LoginActivity";

	private boolean wasBackgrounded;
	private OAuthWebviewHelper webviewHelper;
    private ChangeServerReceiver changeServerReceiver;
    private boolean receiverRegistered;
    private SPRequestHandler spRequestHandler;
    private SPAuthCallback authCallback;
    private String userHint;
    private String spActivityName;
    private Bundle spActivityExtras;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		boolean isDarkTheme = SalesforceSDKManager.getInstance().isDarkTheme();
        setTheme(isDarkTheme ? R.style.SalesforceSDK_Dark_Login : R.style.SalesforceSDK);
        SalesforceSDKManager.getInstance().setViewNavigationVisibility(this);

        // Getting login options from intent's extras.
        final LoginOptions loginOptions = LoginOptions.fromBundle(getIntent().getExtras());

        // Protect against screenshots.
//        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
//                WindowManager.LayoutParams.FLAG_SECURE);

        // Fetches auth config if required.
        try {
            (new AuthConfigTask(null)).execute().get();
        } catch (Exception e) {
            SalesforceSDKLogger.e(TAG, "Exception occurred while fetching auth config", e);
        }

        // Setup content view.
        setContentView(R.layout.sf__login);
		if (SalesforceSDKManager.getInstance().isIDPLoginFlowEnabled()) {
            final Button button = findViewById(R.id.sf__idp_login_button);
            button.setVisibility(View.VISIBLE);
        }

        if (SalesforceSDKManager.getInstance().isBioAuthEnabled()) {
            final Button button = findViewById(R.id.sf__bio_login_button);
            button.setVisibility(View.VISIBLE);

//            presentAuth();
        }

        // Setup the WebView.
        final WebView webView = findViewById(R.id.sf__oauth_webview);
        final WebSettings webSettings = webView.getSettings();
        webSettings.setUseWideViewPort(true);
        webSettings.setLayoutAlgorithm(LayoutAlgorithm.NORMAL);
        webSettings.setJavaScriptEnabled(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setDomStorageEnabled(true);
        EventsObservable.get().notifyEvent(EventType.AuthWebViewCreateComplete, webView);
        webviewHelper = getOAuthWebviewHelper(this, loginOptions, webView, savedInstanceState);

        // Let observers know
        EventsObservable.get().notifyEvent(EventType.LoginActivityCreateComplete, this);
        certAuthOrLogin();
        if (!receiverRegistered) {
			changeServerReceiver = new ChangeServerReceiver();
            final IntentFilter changeServerFilter = new IntentFilter(ServerPickerActivity.CHANGE_SERVER_INTENT);
            registerReceiver(changeServerReceiver, changeServerFilter);
            receiverRegistered = true;
        }
        authCallback = new SPAuthCallback();

        // TODO: check if we are here because current user has expired session and presentAuth()
	}

	@Override
	protected void onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(changeServerReceiver);
            receiverRegistered = false;
        }
        super.onDestroy();
    }

	@Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        // If this is a callback from Chrome, processes it and does nothing else.
        if (isChromeCallback(intent)) {
            completeAuthFlow(intent);
            return;
        }

        // Reloads login page for every new intent to ensure the correct login server is selected.
        if (webviewHelper.shouldReloadPage()) {
            webviewHelper.loadLoginPage();
        }

        // Launches IDP login flow directly for IDP initiated login flow.
        if (intent != null) {
            final Bundle extras = intent.getExtras();
            if (extras != null) {
                userHint = extras.getString(IDPInititatedLoginReceiver.USER_HINT_KEY);
                spActivityName = extras.getString(IDPInititatedLoginReceiver.SP_ACTVITY_NAME_KEY);
                spActivityExtras = extras.getBundle(IDPInititatedLoginReceiver.SP_ACTVITY_EXTRAS_KEY);
                boolean isIdpInitFlow = extras.getBoolean(IDPInititatedLoginReceiver.IDP_INIT_LOGIN_KEY);
                if (isIdpInitFlow) {
                    onIDPLoginClick(null);
                }
            }
        }
    }

    protected void certAuthOrLogin() {
        if (shouldUseCertBasedAuth()) {
            final String alias = RuntimeConfig.getRuntimeConfig(this).getString(ConfigKey.ManagedAppCertAlias);
            SalesforceSDKLogger.d(TAG, "Cert based login flow being triggered with alias: " + alias);
            KeyChain.choosePrivateKeyAlias(this, webviewHelper, null, null, null, -1, alias);
        } else {
            SalesforceSDKLogger.d(TAG, "User agent login flow being triggered");
            webviewHelper.loadLoginPage();
        }
    }

    private boolean isChromeCallback(Intent intent) {
        if (intent == null) {
            return false;
        }
        final Uri uri = intent.getData();
        return (uri != null);
    }

    private void completeAuthFlow(Intent intent) {
        final Uri uri = intent.getData();
        final Map<String, String> params = UriFragmentParser.parse(uri);
        final String error = params.get("error");
        if (error != null) {
            final String errorDesc = params.get("error_description");
            webviewHelper.onAuthFlowError(error, errorDesc, null);
        } else {
            final OAuth2.TokenEndpointResponse tr = new OAuth2.TokenEndpointResponse(params);
            webviewHelper.onAuthFlowComplete(tr);
        }
    }

	/**
     * Returns whether certificate based authentication flow should be used.
     *
     * @return True - if it should be used, False - otherwise.
     */
    protected boolean shouldUseCertBasedAuth() {
		return RuntimeConfig.getRuntimeConfig(this).getBoolean(ConfigKey.RequireCertAuth);
    }

	protected OAuthWebviewHelper getOAuthWebviewHelper(OAuthWebviewHelperEvents callback,
			LoginOptions loginOptions, WebView webView, Bundle savedInstanceState) {
		return new OAuthWebviewHelper(this, callback, loginOptions, webView, savedInstanceState);
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (wasBackgrounded) {
		    if (webviewHelper.shouldReloadPage()) {
                webviewHelper.clearView();
                webviewHelper.loadLoginPage();
            }
			wasBackgrounded = false;
		}
	}

	@Override
	public void onSaveInstanceState(Bundle bundle) {
		super.onSaveInstanceState(bundle);
		webviewHelper.saveState(bundle);
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {

		// This allows sub classes to override the behavior by returning false.
		if (fixBackButtonBehavior(keyCode)) {
			return true;
		}
		return super.onKeyDown(keyCode, event);
	}

	/**
	 * A fix for back button behavior
	 *
	 * @return true if the fix was applied
	 *         false if the key code was not handled
	 */
	protected boolean fixBackButtonBehavior(int keyCode) {
		if (keyCode == KeyEvent.KEYCODE_BACK) {

            /*
             * If there are no accounts signed in, we need the login screen
             * to go away, and go back to the home screen. However, if the
             * login screen has been brought up from the switcher screen,
             * the back button should take the user back to the previous screen.
             */
            final UserAccountManager accMgr = SalesforceSDKManager.getInstance().getUserAccountManager();
            wasBackgrounded = true;
            if (accMgr.getAuthenticatedUsers() == null) {
                moveTaskToBack(true);
            } else {
                finish();
            }
            return true;
		}
		return false;
	}

    @Override
    public void onBackPressed() {
        // purposefully blank

        // TODO: check if in bio auth mode
    }

    /**************************************************************************************************
     *
     * Actions (Changer server / Clear cookies etc) are available through a menu
     *
     **************************************************************************************************/

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.sf__login, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
		if (itemId == R.id.sf__menu_clear_cookies) {
        	onClearCookiesClick(null);
        	return true;
        } else if (itemId == R.id.sf__menu_pick_server) {
        	onPickServerClick(null);
        	return true;
        } else if (itemId == R.id.sf__menu_reload) {
        	onReloadClick(null);
        	return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /**************************************************************************************************
     *
     * Callbacks from the OAuthWebviewHelper
     *
     **************************************************************************************************/

	@Override
	public void loadingLoginPage(String loginUrl) {
		final ActionBar ab = getActionBar();
		if (ab != null) {
			ab.setTitle(loginUrl);
		}
	}

	@Override
	public void onAccountAuthenticatorResult(Bundle authResult) {
//		setAccountAuthenticatorResult(authResult);
	}

    /**************************************************************************************************
     *
     * Buttons click handlers
     *
     **************************************************************************************************/

	/**
	 * Called when "Clear cookies" button is clicked.
	 * Clear cookies and reload login page.
	 * @param v
	 */
	public void onClearCookiesClick(View v) {
		webviewHelper.clearCookies();
		webviewHelper.loadLoginPage();
	}

    /**
     * Called when the IDP login button is clicked.
     *
     * @param v IDP login button.
     */
    public void onIDPLoginClick(View v) {
        final String loginServer = SalesforceSDKManager.getInstance().getLoginServerManager().getSelectedLoginServer().url.trim();
        SalesforceSDKLogger.d(TAG, "Launching IDP app for authentication with login host: " + loginServer);
        spRequestHandler = new SPRequestHandler(loginServer, userHint, authCallback);
        spRequestHandler.launchIDPApp(this);
    }

	/**
	 * Called when "Reload" button is clicked.
	 * Reloads login page.
	 * @param v
	 */
	public void onReloadClick(View v) {
		webviewHelper.loadLoginPage();
	}

	/**
	 * Called when "Pick server" button is clicked.
	 * Start ServerPickerActivity
	 * @param v
	 */
	public void onPickServerClick(View v) {
		final Intent i = new Intent(this, ServerPickerActivity.class);
	    startActivityForResult(i, PICK_SERVER_REQUEST_CODE);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode == SPRequestHandler.IDP_REQUEST_CODE) {
            spRequestHandler.handleIDPResponse(resultCode, data);
        } else {
	        super.onActivityResult(requestCode, resultCode, data);
	    }
	}

	@Override
	public void finish(UserAccount userAccount) {
        if (SalesforceSDKManager.getInstance().isBioAuthEnabled() &&
            SalesforceSDKManager.getInstance().getUserAccountManager().getAuthenticatedUsers().contains(userAccount)) {
            new RefreshTokenTask(this).execute();
        }

        initAnalyticsManager(userAccount);
        final UserAccountManager userAccountManager = SalesforceSDKManager.getInstance().getUserAccountManager();
        final List<UserAccount> authenticatedUsers = userAccountManager.getAuthenticatedUsers();
        final int numAuthenticatedUsers = authenticatedUsers == null ? 0 : authenticatedUsers.size();

        /*
         * Sends user switch intents only if this login flow is not a login triggered due
         * to an incoming authentication request from an SP app or first user to login on IDP.
         * If it is an incoming SP request, we should add the user account but NOT switch to
         * the user or send user switch intents unless it's the first user being logged in.
         */
        boolean isFirstUserOrNotIDPFlow = !SalesforceSDKManager.getInstance().isIDPAppLoginFlowActive()
                || (numAuthenticatedUsers <= 1);
        if (isFirstUserOrNotIDPFlow) {
            final int userSwitchType;
            if (numAuthenticatedUsers == 1) {

                // We've already authenticated the first user, so there should be one.
                userSwitchType = UserAccountManager.USER_SWITCH_TYPE_FIRST_LOGIN;
            } else if (numAuthenticatedUsers > 1) {

                // Otherwise we're logging in with an additional user.
                userSwitchType = UserAccountManager.USER_SWITCH_TYPE_LOGIN;
            } else {

                // This should never happen but if it does, pass in the "unknown" value.
                userSwitchType = UserAccountManager.USER_SWITCH_TYPE_DEFAULT;
            }
            userAccountManager.sendUserSwitchIntent(userSwitchType, null);
        }

        /*
         * Passes back the added user account object if this is a login flow in the IDP app
         * initiated by an incoming request for authentication from an SP app.
         */
        if (userAccount != null && SalesforceSDKManager.getInstance().isIDPAppLoginFlowActive()) {
            final Intent intent = new Intent(IDPAccountPickerActivity.IDP_LOGIN_COMPLETE_ACTION);
            intent.putExtra(IDPAccountPickerActivity.USER_ACCOUNT_KEY, userAccount.toBundle());
            sendBroadcast(intent);
        }

        // If the IDP app specified a component to launch after login, launches that component.
        if (!TextUtils.isEmpty(spActivityName)) {
            try {
                final Intent intent = new Intent(this, Class.forName(spActivityName));
                intent.addCategory(Intent.CATEGORY_DEFAULT);
                intent.putExtra(IDPInititatedLoginReceiver.SP_ACTVITY_EXTRAS_KEY, spActivityExtras);
                startActivity(intent);
            } catch (Exception e) {
                SalesforceSDKLogger.e(TAG, "Could not start activity", e);
            }
        }

        // Cleans up some state before dismissing activity.
        userHint = null;
        spActivityName = null;
        spActivityExtras = null;
        finish();
	}

    private void initAnalyticsManager(UserAccount account) {
        final SalesforceAnalyticsManager analyticsManager = SalesforceAnalyticsManager.getInstance(account);
	    if (analyticsManager != null) {
            analyticsManager.updateLoggingPrefs();
	    }
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        super.onPointerCaptureChanged(hasCapture);
    }

    public class ChangeServerReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && intent.getAction() != null) {
                final String action = intent.getAction();
                if (ServerPickerActivity.CHANGE_SERVER_INTENT.equals(action)) {
                    webviewHelper.loadLoginPage();
                }
            }
        }
    }

    /**
     * Callbacks for SP authentication flow.
     *
     * @author bhariharan
     */
    public class SPAuthCallback {

        /**
         * Called when the flow was successful and token response is received.
         *
         * @param tokenResponse Token response.
         */
        public void receivedTokenResponse(OAuth2.TokenEndpointResponse tokenResponse) {
            webviewHelper.onAuthFlowComplete(tokenResponse);
        }

        /**
         * Called when the flow was not successful.
         *
         * @param errorMessage Error message.
         */
        public void receivedErrorResponse(final String errorMessage) {
            final Handler toastHandler = new Handler(Looper.getMainLooper());
            toastHandler.post(new Runnable() {

                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), errorMessage, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private void presentAuth() {
        BiometricPrompt biometricPrompt = getBiometricPrompt();
        BiometricManager biometricManager = BiometricManager.from(this);

        switch (biometricManager.canAuthenticate(getAuthenticators())) {
            case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE:
            case BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED:
            case BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED:
            case BiometricManager.BIOMETRIC_STATUS_UNKNOWN:
                // This should never happen.
                String error = getString(R.string.sf__screen_lock_error);
                SalesforceSDKLogger.e(TAG, "Biometric manager cannot authenticate. " + error);
                break;
            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                break;
            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                break;
            case BiometricManager.BIOMETRIC_SUCCESS:
                biometricPrompt.authenticate(getPromptInfo());
                break;
        }
    }

    private BiometricPrompt getBiometricPrompt() {
        LoginActivity loginActivity = this;
        return new BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                    }

                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        // refresh token
                        //                            SalesforceSDKManager.getInstance().getClientManager().peekRestClient().getOAuthRefreshInterceptor().refreshAccessToken();

//                        UserAccountManager accountManager = SalesforceSDKManager.getInstance().getUserAccountManager();
//                        accountManager.getUserFromOrgAndUserId(accountManager.getStoredUserId(),
//                                accountManager.getStoredUserId());


                        Log.i("bpage", "trying to get rest client");
                        new RefreshTokenTask(loginActivity).execute();

//                        SalesforceSDKManager.getInstance().getClientManager().getRestClient(loginActivity,
//                                client -> {
//                                    try {
//                                        Log.i("bpage", "client - attempting refresh");
//
//                                        client.getOAuthRefreshInterceptor().refreshAccessToken();
//                                        finish();
//                                    } catch (IOException e) {
//                                        Log.i("bpage", "it broke again");
//                                    }
//                                });
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                    }
                });
    }

    private class RefreshTokenTask extends AsyncTask<Void, Void, Void> {

        private final LoginActivity activity;

        public RefreshTokenTask(LoginActivity activity) {
            this.activity = activity;;
        }

        @Override
        protected Void doInBackground(Void... voids) {
            SalesforceSDKManager.getInstance().getClientManager().getRestClient(activity,
                    client -> {
                        try {
                            Log.i("bpage", "client - attempting refresh");

                            client.getOAuthRefreshInterceptor().refreshAccessToken();
                            activity.finish();
                        } catch (IOException e) {
                            Log.i("bpage", "it broke again");
                        }
                    });

            return null;
        }
    }

    private int getAuthenticators() {
        // TODO: Remove when min API > 29.
        return (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                ? BIOMETRIC_STRONG | DEVICE_CREDENTIAL
                : BIOMETRIC_WEAK | DEVICE_CREDENTIAL;
    }

    private BiometricPrompt.PromptInfo getPromptInfo() {
        return new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Login with Biometric")
                .setAllowedAuthenticators(getAuthenticators())
                .setConfirmationRequired(false)
                .build();
    }

    public void onBioAuthClick(View view) {
        presentAuth();
    }
}
