// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.internal;

import static com.android.tools.r8.utils.AssertionUtils.assertNotNull;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.core.AnyOf.anyOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.android.tools.r8.D8TestBuilder;
import com.android.tools.r8.D8TestCompileResult;
import com.android.tools.r8.R8FullTestBuilder;
import com.android.tools.r8.R8TestCompileResult;
import com.android.tools.r8.TestCompilerBuilder;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.ThrowableConsumer;
import com.android.tools.r8.ToolHelper.DexVm.Version;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class GMSCoreV10Test extends GMSCoreCompilationTestBase {

  private static final Path base = Paths.get("third_party/gmscore/gmscore_v10/");

  private static Path sanitizedLibrary;
  private static Path sanitizedProguardConfiguration;

  private final TestParameters parameters;

  @Parameterized.Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters()
        .withDexRuntime(Version.DEFAULT)
        .withApiLevel(AndroidApiLevel.L)
        .build();
  }

  public GMSCoreV10Test(TestParameters parameters) {
    this.parameters = parameters;
  }

  @BeforeClass
  public static void setup() throws Exception {
    LibrarySanitizer librarySanitizer =
        new LibrarySanitizer(getStaticTemp())
            .addProguardConfigurationFiles(base.resolve(PG_CONF))
            .sanitize();
    sanitizedLibrary = librarySanitizer.getSanitizedLibrary();
    sanitizedProguardConfiguration = librarySanitizer.getSanitizedProguardConfiguration();
  }

  @Test
  public void testR8Determinism() throws Exception {
    Map<String, String> idsRoundOne = new ConcurrentHashMap<>();
    R8TestCompileResult compileResult =
        compileWithR8(
            builder ->
                builder.addOptionsModification(
                    options ->
                        options.testing.processingContextsConsumer =
                            id -> assertNull(idsRoundOne.put(id, id))));

    compileResult.runDex2Oat(parameters.getRuntime()).assertNoVerificationErrors();

    Map<String, String> idsRoundTwo = new ConcurrentHashMap<>();
    R8TestCompileResult otherCompileResult =
        compileWithR8(
            builder ->
                builder.addOptionsModification(
                    options ->
                        options.testing.processingContextsConsumer =
                            id -> {
                              assertNotNull(idsRoundOne.get(id));
                              assertNull(idsRoundTwo.put(id, id));
                            }));

    // Verify that the result of the two compilations was the same.
    assertEquals(
        Collections.emptySet(),
        Sets.symmetricDifference(idsRoundOne.keySet(), idsRoundTwo.keySet()));
    assertIdenticalApplications(compileResult.getApp(), otherCompileResult.getApp());
    assertEquals(compileResult.getProguardMap(), otherCompileResult.getProguardMap());
  }

  @Test
  public void testR8ForceJumboStringProcessing() throws Exception {
    compileWithR8(
            builder ->
                builder.addOptionsModification(
                    options -> options.testing.forceJumboStringProcessing = true))
        .runDex2Oat(parameters.getRuntime())
        .assertNoVerificationErrors();
  }

  @Test
  public void testD8Debug() throws Exception {
    compileWithD8Debug(ThrowableConsumer.empty());
  }

  @Test
  public void testD8DebugLegacyMultidex() throws Exception {
    compileWithD8Debug(
            builder ->
                builder
                    .addMainDexListFiles(base.resolve("main_dex_list.txt"))
                    .setMinApi(AndroidApiLevel.K))
        .runDex2Oat(parameters.getRuntime())
        .assertNoVerificationErrors();
  }

  @Test
  public void testD8DebugLegacyMultidexDexOpt() throws Exception {
    compileWithD8Debug(
            builder ->
                builder
                    .addMainDexListFiles(base.resolve("main_dex_list.txt"))
                    .setMinApi(AndroidApiLevel.K)
                    .setOptimizeMultidexForLinearAlloc())
        .runDex2Oat(parameters.getRuntime())
        .assertNoVerificationErrors();
  }

  @Test
  public void testD8Release() throws Exception {
    compileWithD8Release(ThrowableConsumer.empty())
        .runDex2Oat(parameters.getRuntime())
        .assertNoVerificationErrors();
  }

  @Test
  public void testD8ReleaseLegacyMultidex() throws Exception {
    compileWithD8Release(
            builder ->
                builder
                    .addMainDexListFiles(base.resolve("main_dex_list.txt"))
                    .setMinApi(AndroidApiLevel.K))
        .runDex2Oat(parameters.getRuntime())
        .assertNoVerificationErrors();
  }

  @Test
  public void buildD8ReleaseLegacyMultidexDexOpt() throws Exception {
    compileWithD8Release(
            builder ->
                builder
                    .addMainDexListFiles(base.resolve("main_dex_list.txt"))
                    .setMinApi(AndroidApiLevel.K)
                    .setOptimizeMultidexForLinearAlloc())
        .runDex2Oat(parameters.getRuntime())
        .assertNoVerificationErrors();
  }

  private D8TestCompileResult compileWithD8Debug(ThrowableConsumer<D8TestBuilder> configuration)
      throws Exception {
    return compileWithD8(configuration.andThen(TestCompilerBuilder::debug));
  }

  private D8TestCompileResult compileWithD8Release(ThrowableConsumer<D8TestBuilder> configuration)
      throws Exception {
    return compileWithD8(configuration.andThen(TestCompilerBuilder::release));
  }

  private D8TestCompileResult compileWithD8(ThrowableConsumer<D8TestBuilder> configuration)
      throws Exception {
    return testForD8()
        .addProgramFiles(base.resolve(DEPLOY_JAR))
        .setMinApi(AndroidApiLevel.L)
        .apply(configuration)
        .compile();
  }

  private R8TestCompileResult compileWithR8(ThrowableConsumer<R8FullTestBuilder> configuration)
      throws Exception {
    // Program files are included in Proguard configuration.
    return testForR8(Backend.DEX)
        .addLibraryFiles(sanitizedLibrary)
        .addKeepRuleFiles(sanitizedProguardConfiguration)
        .addDontWarn(
            "android.hardware.location.IActivityRecognitionHardware",
            "android.hardware.location.IFusedLocationHardware",
            "android.location.FusedBatchOptions",
            "android.location.GeocoderParams$1",
            "android.location.ILocationManager",
            "android.media.IRemoteDisplayCallback",
            "android.media.RemoteDisplayState$RemoteDisplayInfo",
            "com.android.internal.location.ProviderProperties",
            "com.android.internal.location.ProviderRequest")
        .allowDiagnosticMessages()
        .allowUnusedProguardConfigurationRules()
        .setMinApi(parameters.getApiLevel())
        .apply(configuration)
        .compile()
        .assertAllInfoMessagesMatch(
            anyOf(
                containsString("Ignoring option: -optimizations"),
                containsString("Proguard configuration rule does not match anything")))
        .assertAllWarningMessagesMatch(containsString("Ignoring option: -outjars"));
  }
}
