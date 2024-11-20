package com.salesforce.androidsdk.ui

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.ViewModel

class LoginViewModel: ViewModel() {
    /*
     * I envision a class similar to this (maybe a different name) being used by the customer to set options and values.
     *
     * Options would include things like using dynamic (web derived) colors for native elements or not.
     * The customer should be allowed to set values like background color of both top and bottom native pieces, title
     * color light, title color dark, menu color, server picker colors, etc
     */

    internal var backgroundColor = mutableStateOf(Color.Blue)
    internal var headerTextColor = derivedStateOf { if (backgroundColor.value.luminance() > 0.5) Color.Black else Color.White }

    internal var selectedSever = mutableStateOf("login.salesforce.com")
    internal var showBottomSheet = mutableStateOf(false)
}