package com.salesforce.androidsdk.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.salesforce.androidsdk.R
import com.salesforce.androidsdk.app.SalesforceSDKManager

class ScreenLockActivity: Activity() {

    private lateinit var keyguard: KeyguardManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // create biometricManager?
        // set theme
        // setViewNavigationVisibility
        // protect against screenshots
        setContentView(R.layout.sf__screen_lock)

        // initialize ui
        val noPinError = findViewById<TextView>(R.id.no_pin_error)
        noPinError.visibility = View.GONE

        keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguard.isDeviceSecure) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                val screenLockIntent = keyguard.createConfirmDeviceCredentialIntent("Your App Name Here", "Please authenticate to use the app.")
                startActivityForResult(screenLockIntent, 1)
            }
        } else {
            noPinError.visibility = View.VISIBLE
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // check request and result code
        SalesforceSDKManager.getInstance().screenLockManager.setShouldLock(false)
        finish()
    }
}