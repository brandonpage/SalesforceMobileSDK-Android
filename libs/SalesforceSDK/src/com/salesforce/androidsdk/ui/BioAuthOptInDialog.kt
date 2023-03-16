package com.salesforce.androidsdk.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.salesforce.androidsdk.app.SalesforceSDKManager

class BioAuthOptInDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            // Use the Builder class for convenient dialog construction
            val builder = AlertDialog.Builder(it)
            builder.setMessage("Enable Biometric Login?")
                .setPositiveButton("Yes!") { _, _ ->
//                    SalesforceSDKManager.getInstance().bioAuthManager.optIn()
                    dismiss()
                }
                .setNegativeButton("No Thanks") { _, _ ->
                    dismiss()
                }
            // Create the AlertDialog object and return it
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }
}