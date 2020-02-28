/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.integrity;

import static android.content.integrity.AppIntegrityManager.EXTRA_STATUS;
import static android.content.integrity.AppIntegrityManager.STATUS_FAILURE;
import static android.content.integrity.AppIntegrityManager.STATUS_SUCCESS;
import static android.content.integrity.InstallerAllowedByManifestFormula.INSTALLER_CERTIFICATE_NOT_EVALUATED;
import static android.content.pm.PackageManager.EXTRA_VERIFICATION_ID;
import static android.content.pm.PackageManager.EXTRA_VERIFICATION_INSTALLER_PACKAGE;
import static android.content.pm.PackageManager.EXTRA_VERIFICATION_INSTALLER_UID;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.integrity.AppInstallMetadata;
import android.content.integrity.AtomicFormula;
import android.content.integrity.IntegrityFormula;
import android.content.integrity.Rule;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;

import androidx.test.InstrumentationRegistry;

import com.android.internal.R;
import com.android.server.integrity.engine.RuleEvaluationEngine;
import com.android.server.integrity.model.IntegrityCheckResult;
import com.android.server.testutils.TestUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Unit test for {@link com.android.server.integrity.AppIntegrityManagerServiceImpl} */
@RunWith(JUnit4.class)
public class AppIntegrityManagerServiceImplTest {
    private static final String TEST_APP_PATH =
            "AppIntegrityManagerServiceImplTest/AppIntegrityManagerServiceTestApp.apk";

    private static final String TEST_APP_TWO_CERT_PATH =
            "AppIntegrityManagerServiceImplTest/DummyAppTwoCerts.apk";

    private static final String PACKAGE_MIME_TYPE = "application/vnd.android.package-archive";
    private static final String VERSION = "version";
    private static final String TEST_FRAMEWORK_PACKAGE = "com.android.frameworks.servicestests";

    private static final String PACKAGE_NAME = "com.test.app";

    private static final long VERSION_CODE = 100;
    private static final String INSTALLER = "com.long.random.test.installer.name";

    // These are obtained by running the test and checking logcat.
    private static final String APP_CERT =
            "C8A2E9BCCF597C2FB6DC66BEE293FC13F2FC47EC77BC6B2B0D52C11F51192AB8";
    // We use SHA256 for package names longer than 32 characters.
    private static final String INSTALLER_SHA256 =
            "30F41A7CBF96EE736A54DD6DF759B50ED3CC126ABCEF694E167C324F5976C227";

    private static final String DUMMY_APP_TWO_CERTS_CERT_1 =
            "C0369C2A1096632429DFA8433068AECEAD00BAC337CA92A175036D39CC9AFE94";
    private static final String DUMMY_APP_TWO_CERTS_CERT_2 =
            "94366E0A80F3A3F0D8171A15760B88E228CD6E1101F0414C98878724FBE70147";

    private static final String PLAY_STORE_PKG = "com.android.vending";
    private static final String ADB_INSTALLER = "adb";
    private static final String PLAY_STORE_CERT = "play_store_cert";

    @org.junit.Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock
    PackageManagerInternal mPackageManagerInternal;
    @Mock
    Context mMockContext;
    @Mock
    Resources mMockResources;
    @Mock
    RuleEvaluationEngine mRuleEvaluationEngine;
    @Mock
    IntegrityFileManager mIntegrityFileManager;
    @Mock
    Handler mHandler;

    private final Context mRealContext = InstrumentationRegistry.getTargetContext();

    private PackageManager mSpyPackageManager;
    private File mTestApk;
    private File mTestApkTwoCerts;

    // under test
    private AppIntegrityManagerServiceImpl mService;

    @Before
    public void setup() throws Exception {
        mTestApk = File.createTempFile("AppIntegrity", ".apk");
        try (InputStream inputStream = mRealContext.getAssets().open(TEST_APP_PATH)) {
            Files.copy(inputStream, mTestApk.toPath(), REPLACE_EXISTING);
        }

        mTestApkTwoCerts = File.createTempFile("AppIntegrityTwoCerts", ".apk");
        try (InputStream inputStream = mRealContext.getAssets().open(TEST_APP_TWO_CERT_PATH)) {
            Files.copy(inputStream, mTestApkTwoCerts.toPath(), REPLACE_EXISTING);
        }

        mService =
                new AppIntegrityManagerServiceImpl(
                        mMockContext,
                        mPackageManagerInternal,
                        mRuleEvaluationEngine,
                        mIntegrityFileManager,
                        mHandler);

        mSpyPackageManager = spy(mRealContext.getPackageManager());
        // setup mocks to prevent NPE
        when(mMockContext.getPackageManager()).thenReturn(mSpyPackageManager);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getStringArray(anyInt())).thenReturn(new String[]{});
        when(mIntegrityFileManager.initialized()).thenReturn(true);
        // These are needed to override the Settings.Global.get result.
        when(mMockContext.getContentResolver()).thenReturn(mRealContext.getContentResolver());
        setIntegrityCheckIncludesRuleProvider(true);
    }

    @After
    public void tearDown() throws Exception {
        mTestApk.delete();
        mTestApkTwoCerts.delete();
    }

    @Test
    public void updateRuleSet_notAuthorized() throws Exception {
        makeUsSystemApp();
        Rule rule =
                new Rule(
                        new AtomicFormula.BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true),
                        Rule.DENY);
        TestUtils.assertExpectException(
                SecurityException.class,
                "Only system packages specified in config_integrityRuleProviderPackages are"
                        + " allowed to call this method.",
                () ->
                        mService.updateRuleSet(
                                VERSION,
                                new ParceledListSlice<>(Arrays.asList(rule)),
                                /* statusReceiver= */ null));
    }

    @Test
    public void updateRuleSet_notSystemApp() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp(false);
        Rule rule =
                new Rule(
                        new AtomicFormula.BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true),
                        Rule.DENY);
        TestUtils.assertExpectException(
                SecurityException.class,
                "Only system packages specified in config_integrityRuleProviderPackages are"
                        + " allowed to call this method.",
                () ->
                        mService.updateRuleSet(
                                VERSION,
                                new ParceledListSlice<>(Arrays.asList(rule)),
                                /* statusReceiver= */ null));
    }

    @Test
    public void updateRuleSet_authorized() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        Rule rule =
                new Rule(
                        new AtomicFormula.BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true),
                        Rule.DENY);

        // no SecurityException
        mService.updateRuleSet(
                VERSION, new ParceledListSlice<>(Arrays.asList(rule)), mock(IntentSender.class));
    }

    @Test
    public void updateRuleSet_correctMethodCall() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        IntentSender mockReceiver = mock(IntentSender.class);
        List<Rule> rules =
                Arrays.asList(
                        new Rule(IntegrityFormula.Application.packageNameEquals(PACKAGE_NAME),
                                Rule.DENY));

        mService.updateRuleSet(VERSION, new ParceledListSlice<>(rules), mockReceiver);
        runJobInHandler();

        verify(mIntegrityFileManager).writeRules(VERSION, TEST_FRAMEWORK_PACKAGE, rules);
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mockReceiver).sendIntent(any(), anyInt(), intentCaptor.capture(), any(), any());
        assertEquals(STATUS_SUCCESS, intentCaptor.getValue().getIntExtra(EXTRA_STATUS, -1));
    }

    @Test
    public void updateRuleSet_fail() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        doThrow(new IOException()).when(mIntegrityFileManager).writeRules(any(), any(), any());
        IntentSender mockReceiver = mock(IntentSender.class);
        List<Rule> rules =
                Arrays.asList(
                        new Rule(IntegrityFormula.Application.packageNameEquals(PACKAGE_NAME),
                                Rule.DENY));

        mService.updateRuleSet(VERSION, new ParceledListSlice<>(rules), mockReceiver);
        runJobInHandler();

        verify(mIntegrityFileManager).writeRules(VERSION, TEST_FRAMEWORK_PACKAGE, rules);
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mockReceiver).sendIntent(any(), anyInt(), intentCaptor.capture(), any(), any());
        assertEquals(STATUS_FAILURE, intentCaptor.getValue().getIntExtra(EXTRA_STATUS, -1));
    }

    @Test
    public void broadcastReceiverRegistration() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        ArgumentCaptor<IntentFilter> intentFilterCaptor =
                ArgumentCaptor.forClass(IntentFilter.class);

        verify(mMockContext).registerReceiver(any(), intentFilterCaptor.capture(), any(), any());
        assertEquals(1, intentFilterCaptor.getValue().countActions());
        assertEquals(
                Intent.ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION,
                intentFilterCaptor.getValue().getAction(0));
        assertEquals(1, intentFilterCaptor.getValue().countDataTypes());
        assertEquals(PACKAGE_MIME_TYPE, intentFilterCaptor.getValue().getDataType(0));
    }

    @Test
    public void handleBroadcast_correctArgs() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext)
                .registerReceiver(broadcastReceiverCaptor.capture(), any(), any(), any());
        Intent intent = makeVerificationIntent();
        when(mRuleEvaluationEngine.evaluate(any())).thenReturn(IntegrityCheckResult.allow());

        broadcastReceiverCaptor.getValue().onReceive(mMockContext, intent);
        runJobInHandler();

        ArgumentCaptor<AppInstallMetadata> metadataCaptor =
                ArgumentCaptor.forClass(AppInstallMetadata.class);
        verify(mRuleEvaluationEngine)
                .evaluate(metadataCaptor.capture());
        AppInstallMetadata appInstallMetadata = metadataCaptor.getValue();
        assertEquals(PACKAGE_NAME, appInstallMetadata.getPackageName());
        assertThat(appInstallMetadata.getAppCertificates()).containsExactly(APP_CERT);
        assertEquals(INSTALLER_SHA256, appInstallMetadata.getInstallerName());
        // we cannot check installer cert because it seems to be device specific.
        assertEquals(VERSION_CODE, appInstallMetadata.getVersionCode());
        assertFalse(appInstallMetadata.isPreInstalled());
        // These are hardcoded in the test apk android manifest
        Map<String, String> allowedInstallers =
                appInstallMetadata.getAllowedInstallersAndCertificates();
        assertEquals(2, allowedInstallers.size());
        assertEquals(PLAY_STORE_CERT, allowedInstallers.get(PLAY_STORE_PKG));
        assertEquals(INSTALLER_CERTIFICATE_NOT_EVALUATED, allowedInstallers.get(ADB_INSTALLER));
    }

    @Test
    public void handleBroadcast_correctArgs_multipleCerts() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext)
                .registerReceiver(broadcastReceiverCaptor.capture(), any(), any(), any());
        Intent intent = makeVerificationIntent();
        intent.setDataAndType(Uri.fromFile(mTestApkTwoCerts), PACKAGE_MIME_TYPE);
        when(mRuleEvaluationEngine.evaluate(any())).thenReturn(IntegrityCheckResult.allow());

        broadcastReceiverCaptor.getValue().onReceive(mMockContext, intent);
        runJobInHandler();

        ArgumentCaptor<AppInstallMetadata> metadataCaptor =
                ArgumentCaptor.forClass(AppInstallMetadata.class);
        verify(mRuleEvaluationEngine).evaluate(metadataCaptor.capture());
        AppInstallMetadata appInstallMetadata = metadataCaptor.getValue();
        assertThat(appInstallMetadata.getAppCertificates()).containsExactly(
                DUMMY_APP_TWO_CERTS_CERT_1, DUMMY_APP_TWO_CERTS_CERT_2);
    }

    @Test
    public void handleBroadcast_allow() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext)
                .registerReceiver(broadcastReceiverCaptor.capture(), any(), any(), any());
        Intent intent = makeVerificationIntent();
        when(mRuleEvaluationEngine.evaluate(any())).thenReturn(IntegrityCheckResult.allow());

        broadcastReceiverCaptor.getValue().onReceive(mMockContext, intent);
        runJobInHandler();

        verify(mPackageManagerInternal)
                .setIntegrityVerificationResult(
                        1, PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW);
    }

    @Test
    public void handleBroadcast_reject() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext)
                .registerReceiver(broadcastReceiverCaptor.capture(), any(), any(), any());
        when(mRuleEvaluationEngine.evaluate(any()))
                .thenReturn(
                        IntegrityCheckResult.deny(
                                Arrays.asList(
                                        new Rule(
                                                new AtomicFormula.BooleanAtomicFormula(
                                                        AtomicFormula.PRE_INSTALLED, false),
                                                Rule.DENY))));
        Intent intent = makeVerificationIntent();

        broadcastReceiverCaptor.getValue().onReceive(mMockContext, intent);
        runJobInHandler();

        verify(mPackageManagerInternal)
                .setIntegrityVerificationResult(
                        1, PackageManagerInternal.INTEGRITY_VERIFICATION_REJECT);
    }

    @Test
    public void handleBroadcast_notInitialized() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        when(mIntegrityFileManager.initialized()).thenReturn(false);
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext)
                .registerReceiver(broadcastReceiverCaptor.capture(), any(), any(), any());
        Intent intent = makeVerificationIntent();
        when(mRuleEvaluationEngine.evaluate(any())).thenReturn(IntegrityCheckResult.allow());

        broadcastReceiverCaptor.getValue().onReceive(mMockContext, intent);
        runJobInHandler();

        // The evaluation will still run since we still evaluate manifest based rules.
        verify(mPackageManagerInternal)
                .setIntegrityVerificationResult(
                        1, PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW);
    }

    @Test
    public void verifierAsInstaller_skipIntegrityVerification() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        setIntegrityCheckIncludesRuleProvider(false);
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext, atLeastOnce())
                .registerReceiver(broadcastReceiverCaptor.capture(), any(), any(), any());
        Intent intent = makeVerificationIntent(TEST_FRAMEWORK_PACKAGE);
        when(mRuleEvaluationEngine.evaluate(any()))
                .thenReturn(IntegrityCheckResult.deny(/* rule= */ null));

        broadcastReceiverCaptor.getValue().onReceive(mMockContext, intent);
        runJobInHandler();

        verify(mPackageManagerInternal)
                .setIntegrityVerificationResult(
                        1, PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW);
    }

    @Test
    public void getCurrentRules() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        Rule rule = new Rule(IntegrityFormula.Application.packageNameEquals("package"), Rule.DENY);
        when(mIntegrityFileManager.readRules(any())).thenReturn(Arrays.asList(rule));

        assertThat(mService.getCurrentRules().getList()).containsExactly(rule);
    }

    private void whitelistUsAsRuleProvider() {
        Resources mockResources = mock(Resources.class);
        when(mockResources.getStringArray(R.array.config_integrityRuleProviderPackages))
                .thenReturn(new String[]{TEST_FRAMEWORK_PACKAGE});
        when(mMockContext.getResources()).thenReturn(mockResources);
    }

    private void runJobInHandler() {
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        // sendMessageAtTime is the first non-final method in the call chain when "post" is invoked.
        verify(mHandler).sendMessageAtTime(messageCaptor.capture(), anyLong());
        messageCaptor.getValue().getCallback().run();
    }

    private void makeUsSystemApp() throws Exception {
        makeUsSystemApp(true);
    }

    private void makeUsSystemApp(boolean isSystemApp) throws Exception {
        PackageInfo packageInfo =
                mRealContext.getPackageManager().getPackageInfo(TEST_FRAMEWORK_PACKAGE, 0);
        if (isSystemApp) {
            packageInfo.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        } else {
            packageInfo.applicationInfo.flags &= ~ApplicationInfo.FLAG_SYSTEM;
        }
        doReturn(packageInfo)
                .when(mSpyPackageManager)
                .getPackageInfo(eq(TEST_FRAMEWORK_PACKAGE), anyInt());
        when(mMockContext.getPackageManager()).thenReturn(mSpyPackageManager);
    }

    private Intent makeVerificationIntent() throws Exception {
        PackageInfo packageInfo =
                mRealContext
                        .getPackageManager()
                        .getPackageInfo(TEST_FRAMEWORK_PACKAGE,
                                PackageManager.GET_SIGNING_CERTIFICATES);
        doReturn(packageInfo).when(mSpyPackageManager).getPackageInfo(eq(INSTALLER), anyInt());
        doReturn(1).when(mSpyPackageManager).getPackageUid(eq(INSTALLER), anyInt());
        return makeVerificationIntent(INSTALLER);
    }

    private Intent makeVerificationIntent(String installer) throws Exception {
        Intent intent = new Intent();
        intent.setDataAndType(Uri.fromFile(mTestApk), PACKAGE_MIME_TYPE);
        intent.setAction(Intent.ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION);
        intent.putExtra(EXTRA_VERIFICATION_ID, 1);
        intent.putExtra(Intent.EXTRA_PACKAGE_NAME, PACKAGE_NAME);
        intent.putExtra(EXTRA_VERIFICATION_INSTALLER_PACKAGE, installer);
        intent.putExtra(
                EXTRA_VERIFICATION_INSTALLER_UID,
                mMockContext.getPackageManager().getPackageUid(installer, /* flags= */ 0));
        intent.putExtra(Intent.EXTRA_LONG_VERSION_CODE, VERSION_CODE);
        return intent;
    }

    private void setIntegrityCheckIncludesRuleProvider(boolean shouldInclude) throws Exception {
        int value = shouldInclude ? 1 : 0;
        Settings.Global.putInt(mRealContext.getContentResolver(),
                Settings.Global.INTEGRITY_CHECK_INCLUDES_RULE_PROVIDER, value);
        assertThat(Settings.Global.getInt(mRealContext.getContentResolver(),
                Settings.Global.INTEGRITY_CHECK_INCLUDES_RULE_PROVIDER, -1) == 1).isEqualTo(
                shouldInclude);
    }
}
