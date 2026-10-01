/*
 * Copyright (c) 2014-present, salesforce.com, inc.
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
package com.salesforce.androidsdk;

import android.os.Debug;
import android.os.SystemClock;
import android.util.Log;

import com.salesforce.androidsdk.rest.RestClient;
import com.salesforce.androidsdk.ui.SalesforceActivity;

/**
 * Mock main activity.
 *
 * @author bhariharan
 */
public class MainActivity extends SalesforceActivity {

    private static final String PERF_TAG = "MSDK_PERF";
    private static volatile long lastResumeWallNanos;
    private static volatile long lastResumeCpuNanos;

    @Override
    public void onResume() {
        final long cpuStart = Debug.threadCpuTimeNanos();
        final long wallStart = SystemClock.elapsedRealtimeNanos();
        super.onResume();
        lastResumeWallNanos = SystemClock.elapsedRealtimeNanos() - wallStart;
        lastResumeCpuNanos = Debug.threadCpuTimeNanos() - cpuStart;
        Log.i(PERF_TAG, "activity_on_resume wall_us=" + (lastResumeWallNanos / 1000.0)
                + " cpu_us=" + (lastResumeCpuNanos / 1000.0));
    }

    public static long getLastResumeWallNanos() {
        return lastResumeWallNanos;
    }

    public static long getLastResumeCpuNanos() {
        return lastResumeCpuNanos;
    }

    @Override
    public void onResume(RestClient client) {
        // The benchmark intentionally performs no rendering or network I/O after client creation.
    }
}
