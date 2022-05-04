// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.desugar.desugaredlibrary.test;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.LibraryDesugaringTestConfiguration;
import com.android.tools.r8.R8TestBuilder;
import com.android.tools.r8.StringResource;
import com.android.tools.r8.TestBase.Backend;
import com.android.tools.r8.TestCompilerBuilder;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestRunResult;
import com.android.tools.r8.TestRuntime;
import com.android.tools.r8.desugar.desugaredlibrary.DesugaredLibraryTestBase;
import com.android.tools.r8.ir.desugar.desugaredlibrary.DesugaredLibrarySpecificationParser;
import com.android.tools.r8.utils.InternalOptions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.junit.Assume;

public class DesugaredLibraryTestBuilder<T extends DesugaredLibraryTestBase> {

  private final T test;
  private final TestParameters parameters;
  private final LibraryDesugaringSpecification libraryDesugaringSpecification;
  private final CompilationSpecification compilationSpecification;
  private final TestCompilerBuilder<?, ?, ?, ?, ?> builder;

  private CustomLibrarySpecification customLibrarySpecification = null;
  private Consumer<InternalOptions> l8OptionModifier = null;
  private TestingKeepRuleConsumer keepRuleConsumer = null;

  public DesugaredLibraryTestBuilder(
      T test,
      TestParameters parameters,
      LibraryDesugaringSpecification libraryDesugaringSpecification,
      CompilationSpecification runSpecification) {
    this.test = test;
    this.parameters = parameters;
    this.libraryDesugaringSpecification = libraryDesugaringSpecification;
    this.compilationSpecification = runSpecification;
    this.builder = generateBuilder();
    setUp();
  }

  private void setUp() {
    builder
        .addLibraryFiles(libraryDesugaringSpecification.getAndroidJar())
        .setMinApi(parameters.getApiLevel());
    LibraryDesugaringTestConfiguration.Builder libraryConfBuilder =
        LibraryDesugaringTestConfiguration.builder()
            .setMinApi(parameters.getApiLevel())
            .addDesugaredLibraryConfiguration(
                StringResource.fromFile(libraryDesugaringSpecification.getSpecification()))
            .dontAddRunClasspath();
    if (compilationSpecification.isL8Shrink()) {
      keepRuleConsumer = new TestingKeepRuleConsumer();
      libraryConfBuilder.setKeepRuleConsumer(keepRuleConsumer);
    }
    builder.enableCoreLibraryDesugaring(libraryConfBuilder.build());
  }

  private TestCompilerBuilder<?, ?, ?, ?, ?> generateBuilder() {
    if (compilationSpecification.isCfToCf()) {
      assert !compilationSpecification.isProgramShrink();
      return test.testForD8(Backend.CF);
    }
    // Cf back-end is only allowed in Cf to cf compilations.
    Assume.assumeTrue(parameters.getBackend().isDex());
    if (compilationSpecification.isProgramShrink()) {
      return test.testForR8(parameters.getBackend());
    }
    return test.testForD8(Backend.DEX);
  }

  public DesugaredLibraryTestBuilder<T> setCustomLibrarySpecification(
      CustomLibrarySpecification customLibrarySpecification) {
    this.customLibrarySpecification = customLibrarySpecification;
    builder.addLibraryClasses(customLibrarySpecification.getClasses());
    return this;
  }

  public DesugaredLibraryTestBuilder<T> addOptionsModification(
      Consumer<InternalOptions> optionModifier) {
    builder.addOptionsModification(optionModifier);
    return this;
  }

  public DesugaredLibraryTestBuilder<T> addInnerClasses(Class<?>... clazz) throws IOException {
    builder.addInnerClasses(clazz);
    return this;
  }

  public DesugaredLibraryTestBuilder<T> addProgramClasses(Class<?>... clazz) throws IOException {
    builder.addProgramClasses(clazz);
    return this;
  }

  public DesugaredLibraryTestBuilder<T> addProgramFiles(Path... files) {
    builder.addProgramFiles(files);
    return this;
  }

  private void withR8TestBuilder(Consumer<R8TestBuilder<?>> consumer) {
    if (!builder.isTestShrinkerBuilder()) {
      return;
    }
    consumer.accept((R8TestBuilder<?>) builder);
  }

  public DesugaredLibraryTestBuilder<T> allowDiagnosticWarningMessages() {
    withR8TestBuilder(R8TestBuilder::allowDiagnosticWarningMessages);
    return this;
  }

  public DesugaredLibraryTestBuilder<T> addKeepMainRule(Class<?> clazz) {
    withR8TestBuilder(b -> b.addKeepMainRule(clazz));
    return this;
  }

  public DesugaredLibraryTestBuilder<T> enableInliningAnnotations() {
    withR8TestBuilder(R8TestBuilder::enableInliningAnnotations);
    return this;
  }

  public DesugaredLibraryTestCompileResult<T> compile() throws CompilationFailedException {
    return new DesugaredLibraryTestCompileResult<>(
        test,
        builder.compile(),
        parameters,
        libraryDesugaringSpecification,
        compilationSpecification,
        customLibrarySpecification,
        l8OptionModifier,
        keepRuleConsumer == null ? null : keepRuleConsumer.get());
  }

  public TestRunResult<?> run(TestRuntime runtime, Class<?> mainClass, String... args)
      throws Exception {
    return compile().run(runtime, mainClass.getTypeName(), args);
  }

  public TestRunResult<?> run(TestRuntime runtime, String mainClass, String... args)
      throws Exception {
    return compile().run(runtime, mainClass, args);
  }

  public DesugaredLibraryTestBuilder<T> supportAllCallbacksFromLibrary(
      boolean supportAllCallbacksFromLibrary) {
    builder.addOptionsModification(
        opt ->
            opt.setDesugaredLibrarySpecification(
                DesugaredLibrarySpecificationParser.parseDesugaredLibrarySpecificationforTesting(
                    StringResource.fromFile(libraryDesugaringSpecification.getSpecification()),
                    opt.dexItemFactory(),
                    opt.reporter,
                    false,
                    parameters.getApiLevel().getLevel(),
                    builder ->
                        builder.setSupportAllCallbacksFromLibrary(
                            supportAllCallbacksFromLibrary))));
    return this;
  }
}
