/*
 * Copyright (c) 2011-present, salesforce.com, inc.
 * All rights reserved.
 */
package com.salesforce.androidsdk.performance;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.lifecycle.Lifecycle;

import com.salesforce.androidsdk.MainActivity;
import com.salesforce.androidsdk.TestForceApp;
import com.salesforce.androidsdk.accounts.UserAccount;
import com.salesforce.androidsdk.accounts.UserAccountBuilder;
import com.salesforce.androidsdk.accounts.UserAccountManager;
import com.salesforce.androidsdk.accounts.UserAccountTest;
import com.salesforce.androidsdk.app.SalesforceSDKManager;
import com.salesforce.androidsdk.auth.AuthenticatorService;
import com.salesforce.androidsdk.rest.ClientManager;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * On-device latency harness for the authenticated account/client paths.
 *
 * <p>Run directly with {@code am instrument}; Android Test Orchestrator is intentionally not used.
 * Each invocation recreates deterministic local accounts and performs no network requests.</p>
 */
@RunWith(AndroidJUnit4.class)
public class AccountPathBenchmarkTest {

    private static final String VERSION = "v14-rc3";
    private static final List<String> PUBLISHER_ADDITIONAL_OAUTH_KEYS = Arrays.asList(
            "lightning_domain",
            "visualforce_domain",
            "lightning_sid",
            "content_domain",
            "content_sid",
            "cookie-clientSrc",
            "cookie-sid_Client",
            "sidCookieName",
            "__Secure-has-sid",
            "parent_sid",
            "token_format"
    );
    private static volatile Object sink;

    private Instrumentation instrumentation;
    private Context context;
    private SalesforceSDKManager sdkManager;
    private UserAccountManager userAccountManager;
    private AccountManager accountManager;
    private String accountType;
    private int accountCount;
    private List<UserAccount> users;
    private UserAccount currentUser;
    private Account currentAccount;

    @Before
    public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();

        if (!SalesforceSDKManager.hasInstance()) {
            final Application app = Instrumentation.newApplication(TestForceApp.class, context);
            instrumentation.callApplicationOnCreate(app);
        }

        sdkManager = SalesforceSDKManager.getInstance();
        userAccountManager = sdkManager.getUserAccountManager();
        accountManager = AccountManager.get(context);
        accountType = sdkManager.getAccountType();
        accountCount = Integer.parseInt(
                InstrumentationRegistry.getArguments().getString("accounts", "1")
        );
        seedAccounts(accountCount);
    }

    @Test
    public void seedForLaunch() {
        emitSingle("seed_accounts", 0L, 0L);
    }

    @Test
    public void clearBenchmarkAccounts() {
        int removed = 0;
        for (Account account : accountManager.getAccountsByType(accountType)) {
            if (account.name.startsWith("perf-account-")) {
                Assert.assertTrue(accountManager.removeAccountExplicitly(account));
                removed++;
            }
        }
        Assert.assertTrue(context.getSharedPreferences("current_user_info", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit());
        emitSingle("clear_benchmark_accounts_" + removed, 0L, 0L);
    }

    @Test
    public void benchmarkHotPaths() throws Exception {
        measure("account_manager_get_accounts", 50, 1000,
                () -> accountManager.getAccountsByType(accountType));
        measure("account_manager_get_user_data", 50, 1000,
                () -> accountManager.getUserData(currentAccount, AuthenticatorService.KEY_ORG_ID));
        measure("account_manager_get_password", 50, 1000,
                () -> accountManager.getPassword(currentAccount));

        measure("build_user_account", 20, 200,
                () -> userAccountManager.buildUserAccount(currentAccount));
        measure("all_authenticated_users", 5, 100,
                () -> userAccountManager.getAuthenticatedUsers());
        measure("current_user", 20, 200,
                () -> userAccountManager.getCurrentUser());
        measure("build_account_reverse_lookup", 20, 200,
                () -> userAccountManager.buildAccount(currentUser));
        measure("user_agent", 20, 200,
                () -> sdkManager.getUserAgent(""));

        final Method hydrate = SalesforceSDKManager.class
                .getDeclaredMethod("hydratePerUserFeatures");
        hydrate.setAccessible(true);
        measure("startup_feature_hydration", 5, 100, () -> hydrate.invoke(sdkManager));

        final TimedValue firstClient = time(() -> {
            final ClientManager manager = new ClientManager(context, currentUser);
            return manager.peekRestClient();
        });
        emitSingle("first_client_construction", firstClient.wallNanos, firstClient.cpuNanos);

        measure("client_manager_constructor", 10, 100,
                () -> new ClientManager(context, currentUser));

        final ClientManager providerManager = new ClientManager(context, currentUser);
        measure("provider_constructor", 10, 100,
                () -> new ClientManager.AccMgrAuthTokenProvider(providerManager));

        measure("later_client_construction", 10, 100, () -> {
            final ClientManager manager = new ClientManager(context, currentUser);
            return manager.peekRestClient();
        });
    }

    /**
     * Reproduces Publisher's authenticated Application.onCreate bootstrap shape:
     * resolve currentUser, create the first user-bound ClientManager, and create
     * the RestClient that Publisher then caches in UserSessionInfo. Publisher
     * also registers eleven additional OAuth keys, so retain those duplicate
     * persisted-field reads in this app-specific probe.
     */
    @Test
    public void benchmarkPublisherBootstrap() throws Exception {
        sdkManager.setAdditionalOauthKeys(PUBLISHER_ADDITIONAL_OAUTH_KEYS);
        try {
            measure("publisher_current_user", 20, 200,
                    () -> userAccountManager.getCurrentUser());

            measure("publisher_user_agent", 20, 200,
                    () -> sdkManager.getUserAgent(""));

            measure("publisher_user_client_bootstrap", 10, 100, () -> {
                final UserAccount user = userAccountManager.getCurrentUser();
                final ClientManager manager = new ClientManager(context, user);
                return manager.peekRestClient();
            });
        } finally {
            sdkManager.setAdditionalOauthKeys(null);
        }
    }

    @Test
    public void benchmarkWarmResume() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            for (int i = 0; i < 10; i++) {
                scenario.moveToState(Lifecycle.State.CREATED);
                scenario.moveToState(Lifecycle.State.RESUMED);
            }

            final long[] wall = new long[100];
            final long[] cpu = new long[100];
            for (int i = 0; i < wall.length; i++) {
                scenario.moveToState(Lifecycle.State.CREATED);
                scenario.moveToState(Lifecycle.State.RESUMED);
                wall[i] = MainActivity.getLastResumeWallNanos();
                cpu[i] = MainActivity.getLastResumeCpuNanos();
                Assert.assertTrue("The activity did not record its resume", wall[i] > 0L);
            }
            emitStats("activity_sdk_on_resume", wall, cpu);
        }
    }

    private void seedAccounts(int count) {
        for (Account account : accountManager.getAccountsByType(accountType)) {
            accountManager.removeAccountExplicitly(account);
        }

        users = new ArrayList<>(count);
        final UserAccount base = UserAccountTest.createTestAccount();
        for (int i = 0; i < count; i++) {
            final UserAccount user = UserAccountBuilder.getInstance()
                    .populateFromUserAccount(base)
                    .orgId("perf-org-" + i)
                    .userId("perf-user-" + i)
                    .username("perf-user-" + i + "@example.invalid")
                    .accountName("perf-account-" + i)
                    .build();
            userAccountManager.createAccount(user);
            users.add(user);
        }

        currentUser = users.get(0);
        userAccountManager.storeCurrentUserInfo(currentUser.getUserId(), currentUser.getOrgId());
        // Production correctly uses apply(), but a seed-only instrumentation process can exit
        // before that asynchronous preference write reaches disk. Commit the same synthetic
        // identity here so a subsequent cold app process observes deterministic state.
        Assert.assertTrue(context.getSharedPreferences("current_user_info", Context.MODE_PRIVATE)
                .edit()
                .putString("user_id", currentUser.getUserId())
                .putString("org_id", currentUser.getOrgId())
                .commit());
        currentAccount = userAccountManager.buildAccount(currentUser);
        Assert.assertNotNull(currentAccount);
        Assert.assertEquals(count, accountManager.getAccountsByType(accountType).length);
    }

    private void measure(String operation, int warmups, int samples, Operation operationBody)
            throws Exception {
        for (int i = 0; i < warmups; i++) {
            sink = operationBody.run();
        }

        final long[] wall = new long[samples];
        final long[] cpu = new long[samples];
        for (int i = 0; i < samples; i++) {
            final TimedValue value = time(operationBody);
            wall[i] = value.wallNanos;
            cpu[i] = value.cpuNanos;
            sink = value.value;
        }
        emitStats(operation, wall, cpu);
    }

    private TimedValue time(Operation operation) throws Exception {
        final long cpuStart = Debug.threadCpuTimeNanos();
        final long wallStart = SystemClock.elapsedRealtimeNanos();
        final Object value = operation.run();
        final long wallNanos = SystemClock.elapsedRealtimeNanos() - wallStart;
        final long cpuNanos = Debug.threadCpuTimeNanos() - cpuStart;
        sink = value;
        return new TimedValue(value, wallNanos, cpuNanos);
    }

    private void emitStats(String operation, long[] wall, long[] cpu) {
        final long[] sortedWall = wall.clone();
        final long[] sortedCpu = cpu.clone();
        Arrays.sort(sortedWall);
        Arrays.sort(sortedCpu);
        emit(String.format(Locale.US,
                "PERF_RESULT version=%s accounts=%d operation=%s samples=%d " +
                        "wall_mean_us=%.3f wall_p50_us=%.3f wall_p95_us=%.3f " +
                        "wall_min_us=%.3f wall_max_us=%.3f " +
                        "cpu_mean_us=%.3f cpu_p50_us=%.3f cpu_p95_us=%.3f",
                VERSION, accountCount, operation, wall.length,
                meanMicros(wall), micros(percentile(sortedWall, 0.50)),
                micros(percentile(sortedWall, 0.95)), micros(sortedWall[0]),
                micros(sortedWall[sortedWall.length - 1]), meanMicros(cpu),
                micros(percentile(sortedCpu, 0.50)), micros(percentile(sortedCpu, 0.95))));
    }

    private void emitSingle(String operation, long wallNanos, long cpuNanos) {
        emit(String.format(Locale.US,
                "PERF_RESULT version=%s accounts=%d operation=%s samples=1 " +
                        "wall_mean_us=%.3f wall_p50_us=%.3f wall_p95_us=%.3f " +
                        "wall_min_us=%.3f wall_max_us=%.3f " +
                        "cpu_mean_us=%.3f cpu_p50_us=%.3f cpu_p95_us=%.3f",
                VERSION, accountCount, operation,
                micros(wallNanos), micros(wallNanos), micros(wallNanos),
                micros(wallNanos), micros(wallNanos),
                micros(cpuNanos), micros(cpuNanos), micros(cpuNanos)));
    }

    private void emit(String line) {
        final Bundle status = new Bundle();
        status.putString("perf_result", line);
        instrumentation.sendStatus(2, status);
    }

    private static long percentile(long[] sorted, double quantile) {
        final int index = (int) Math.floor(quantile * (sorted.length - 1));
        return sorted[index];
    }

    private static double meanMicros(long[] nanos) {
        double total = 0.0;
        for (long value : nanos) {
            total += value;
        }
        return total / nanos.length / 1000.0;
    }

    private static double micros(long nanos) {
        return nanos / 1000.0;
    }

    private interface Operation {
        Object run() throws Exception;
    }

    private static final class TimedValue {
        final Object value;
        final long wallNanos;
        final long cpuNanos;

        TimedValue(Object value, long wallNanos, long cpuNanos) {
            this.value = value;
            this.wallNanos = wallNanos;
            this.cpuNanos = cpuNanos;
        }
    }
}
