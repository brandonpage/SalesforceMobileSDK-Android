package com.salesforce.samples.restexplorer;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.firebase.messaging.FirebaseMessaging;
import com.salesforce.androidsdk.push.PushNotificationInterface;

import java.util.Map;

public class PushTest implements PushNotificationInterface {
    @Override
    public void onPushMessageReceived(@Nullable Map<String, String> data) {
        Log.i("bpage", "push received");
    }

    @Nullable
    @Override
    public FirebaseMessaging supplyFirebaseMessaging() {
        return null;
    }
}
