// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.resolution.duplicatedefinitions;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestCompilerBuilder;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.TestRunResult;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppInfoWithClassHierarchy;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.ZipUtils.ZipBuilder;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
/**
 * This is testing resolving Main.f for:
 *
 * <pre>
 * I: I_L { }, I_P { f }
 * class Main implements I
 * </pre>
 */
public class MaximallySpecificSingleProgramPartialTest extends TestBase {

  private static final String UNEXPECTED = "I::foo";

  @Parameter() public TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimesAndApiLevels().build();
  }

  private Path libraryClasses;

  @Before
  public void setup() throws Exception {
    libraryClasses = temp.newFile("lib.jar").toPath();
    ZipBuilder.builder(libraryClasses)
        .addFilesRelative(
            ToolHelper.getClassPathForTests(), ToolHelper.getClassFileForTestClass(I.class))
        .build();
  }

  @Test
  public void testResolution() throws Exception {
    assumeTrue(parameters.useRuntimeAsNoneRuntime());
    AndroidApp.Builder builder = AndroidApp.builder();
    builder.addClassProgramData(getMainImplementingI()).addClassProgramData(getIProgram());
    builder.addLibraryFiles(parameters.getDefaultRuntimeLibrary(), libraryClasses);
    AppView<AppInfoWithClassHierarchy> appView =
        computeAppViewWithClassHierarchy(
            builder.build(), null, options -> options.loadAllClassDefinitions = true);
    AppInfoWithClassHierarchy appInfo = appView.appInfo();
    DexMethod method = buildNullaryVoidMethod(Main.class, "foo", appInfo.dexItemFactory());
    // TODO(b/214382176): Extend resolution to support multiple definition results.
    assertThrows(
        Unreachable.class,
        () -> {
          appInfo.unsafeResolveMethodDueToDexFormat(method);
        });
  }

  @Test
  public void testJvm() throws Exception {
    assumeTrue(parameters.isCfRuntime());
    testForJvm()
        .addRunClasspathFiles(libraryClasses)
        .addProgramClassFileData(getMainImplementingI(), getIProgram())
        .run(parameters.getRuntime(), Main.class)
        .assertFailureWithErrorThatThrows(NoSuchMethodError.class);
  }

  @Test
  public void testD8() throws Exception {
    assumeTrue(parameters.isDexRuntime());
    // TODO(b/214382176): Extend resolution to support multiple definition results.
    runTest(testForD8(parameters.getBackend()))
        .assertFailureWithErrorThatThrowsIf(
            parameters.canUseDefaultAndStaticInterfaceMethods(), NoSuchMethodError.class)
        .assertSuccessWithOutputLinesIf(
            !parameters.canUseDefaultAndStaticInterfaceMethods(), UNEXPECTED);
  }

  @Test
  public void testR8() throws Exception {
    // TODO(b/214382176): Extend resolution to support multiple definition results.
    runTest(testForR8(parameters.getBackend()).addKeepMainRule(Main.class))
        .assertFailureWithErrorThatThrowsIf(
            parameters.canUseDefaultAndStaticInterfaceMethods(), NoSuchMethodError.class)
        .assertSuccessWithOutputLinesIf(
            !parameters.canUseDefaultAndStaticInterfaceMethods(), UNEXPECTED);
  }

  private TestRunResult<?> runTest(TestCompilerBuilder<?, ?, ?, ?, ?> testBuilder)
      throws Exception {
    return testBuilder
        .addProgramClassFileData(getMainImplementingI(), getIProgram())
        .addDefaultRuntimeLibrary(parameters)
        .addLibraryFiles(libraryClasses)
        .setMinApi(parameters.getApiLevel())
        .addOptionsModification(options -> options.loadAllClassDefinitions = true)
        .compile()
        .addBootClasspathFiles(buildOnDexRuntime(parameters, libraryClasses))
        .run(parameters.getRuntime(), Main.class);
  }

  private byte[] getIProgram() throws Exception {
    return transformer(IProgram.class).setClassDescriptor(descriptor(I.class)).transform();
  }

  private byte[] getMainImplementingI() throws Exception {
    return transformer(Main.class).setImplements(I.class).transform();
  }

  public interface I {}

  public interface IProgram {
    default void foo() {
      System.out.println("I::foo");
    }
  }

  public static class Main implements /* I */ IProgram {

    public static void main(String[] args) {
      new Main().foo();
    }
  }
}