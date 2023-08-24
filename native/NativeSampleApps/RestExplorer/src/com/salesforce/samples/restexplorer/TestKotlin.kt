package com.salesforce.samples.restexplorer

import com.salesforce.androidsdk.app.SalesforceSDKManager
import java.util.regex.Pattern

class TestKotlin {
    fun test() {
        SalesforceSDKManager.getInstance().customDomainInferencePattern = Pattern.compile("")
    }
}