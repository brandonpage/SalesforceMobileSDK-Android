package com.salesforce.androidsdk.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import com.salesforce.androidsdk.app.SalesforceSDKManager

private val DarkColorScheme = darkColorScheme(

)

@Composable
fun WebviewTheme(
    darkTheme: Boolean = SalesforceSDKManager.getInstance().isDarkTheme,
    content: @Composable () -> Unit,
) {
    MaterialTheme(DarkColorScheme, content = content)
}


