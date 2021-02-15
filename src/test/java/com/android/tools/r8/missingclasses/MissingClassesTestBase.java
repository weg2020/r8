// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.missingclasses;

import static com.android.tools.r8.DiagnosticsMatcher.diagnosticType;
import static org.junit.Assert.assertEquals;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.R8FullTestBuilder;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestCompilerBuilder.DiagnosticsConsumer;
import com.android.tools.r8.TestDiagnosticMessages;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.ThrowableConsumer;
import com.android.tools.r8.diagnostic.internal.MissingDefinitionsDiagnosticImpl;
import com.android.tools.r8.references.ClassReference;
import com.android.tools.r8.references.FieldReference;
import com.android.tools.r8.references.MethodReference;
import com.android.tools.r8.references.Reference;
import com.android.tools.r8.utils.FieldReferenceUtils;
import com.android.tools.r8.utils.InternalOptions.TestingOptions;
import com.android.tools.r8.utils.MethodReferenceUtils;
import com.google.common.collect.ImmutableSet;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.function.Function;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public abstract class MissingClassesTestBase extends TestBase {

  static class MissingClass extends RuntimeException {

    static int FIELD;

    int field;
  }

  @Retention(RetentionPolicy.RUNTIME)
  @interface MissingRuntimeAnnotation {}

  interface MissingInterface {}

  private final TestParameters parameters;

  @Parameters(name = "{0}")
  public static List<Object[]> data() {
    return buildParameters(getTestParameters().withAllRuntimesAndApiLevels().build());
  }

  public MissingClassesTestBase(TestParameters parameters) {
    this.parameters = parameters;
  }

  public ThrowableConsumer<R8FullTestBuilder> addDontWarn(Class<?> clazz) {
    return builder -> builder.addDontWarn(clazz);
  }

  public ThrowableConsumer<R8FullTestBuilder> addIgnoreWarnings() {
    return addIgnoreWarnings(true);
  }

  public ThrowableConsumer<R8FullTestBuilder> addIgnoreWarnings(
      boolean allowDiagnosticWarningMessages) {
    return builder ->
        builder.addIgnoreWarnings().allowDiagnosticWarningMessages(allowDiagnosticWarningMessages);
  }

  public void compileWithExpectedDiagnostics(
      Class<?> mainClass, DiagnosticsConsumer diagnosticsConsumer)
      throws CompilationFailedException {
    compileWithExpectedDiagnostics(mainClass, diagnosticsConsumer, null);
  }

  public void compileWithExpectedDiagnostics(
      Class<?> mainClass,
      DiagnosticsConsumer diagnosticsConsumer,
      ThrowableConsumer<R8FullTestBuilder> configuration)
      throws CompilationFailedException {
    internalCompileWithExpectedDiagnostics(
        diagnosticsConsumer,
        builder ->
            builder.addProgramClasses(mainClass).addKeepMainRule(mainClass).apply(configuration));
  }

  public void compileWithExpectedDiagnostics(
      ThrowableConsumer<R8FullTestBuilder> configuration, DiagnosticsConsumer diagnosticsConsumer)
      throws CompilationFailedException {
    internalCompileWithExpectedDiagnostics(diagnosticsConsumer, configuration);
  }

  private void internalCompileWithExpectedDiagnostics(
      DiagnosticsConsumer diagnosticsConsumer, ThrowableConsumer<R8FullTestBuilder> configuration)
      throws CompilationFailedException {
    testForR8(parameters.getBackend())
        .apply(configuration)
        .addOptionsModification(TestingOptions::enableExperimentalMissingClassesReporting)
        .setMinApi(parameters.getApiLevel())
        .compileWithExpectedDiagnostics(diagnosticsConsumer);
  }

  ClassReference getMissingClassReference() {
    return Reference.classFromClass(MissingClass.class);
  }

  void inspectDiagnosticsWithIgnoreWarnings(
      TestDiagnosticMessages diagnostics, ClassReference referencedFrom) {
    inspectDiagnosticsWithIgnoreWarnings(
        diagnostics,
        getExpectedDiagnosticMessageWithIgnoreWarnings(
            referencedFrom, ClassReference::getTypeName));
  }

  void inspectDiagnosticsWithIgnoreWarnings(
      TestDiagnosticMessages diagnostics, FieldReference referencedFrom) {
    inspectDiagnosticsWithIgnoreWarnings(
        diagnostics,
        getExpectedDiagnosticMessageWithIgnoreWarnings(
            referencedFrom, FieldReferenceUtils::toSourceString));
  }

  void inspectDiagnosticsWithIgnoreWarnings(
      TestDiagnosticMessages diagnostics, MethodReference referencedFrom) {
    inspectDiagnosticsWithIgnoreWarnings(
        diagnostics,
        getExpectedDiagnosticMessageWithIgnoreWarnings(
            referencedFrom, MethodReferenceUtils::toSourceString));
  }

  void inspectDiagnosticsWithIgnoreWarnings(
      TestDiagnosticMessages diagnostics, String expectedDiagnosticMessage) {
    MissingDefinitionsDiagnosticImpl diagnostic =
        diagnostics
            .assertOnlyWarnings()
            .assertWarningsCount(1)
            .assertAllWarningsMatch(diagnosticType(MissingDefinitionsDiagnosticImpl.class))
            .getWarning(0);
    assertEquals(ImmutableSet.of(getMissingClassReference()), diagnostic.getMissingClasses());
    assertEquals(expectedDiagnosticMessage, diagnostic.getDiagnosticMessage());
  }

  private <T> String getExpectedDiagnosticMessageWithIgnoreWarnings(
      T referencedFrom, Function<T, String> toSourceStringFunction) {
    return "Missing class "
        + getMissingClassReference().getTypeName()
        + " (referenced from: "
        + toSourceStringFunction.apply(referencedFrom)
        + ")";
  }

  void inspectDiagnosticsWithNoRules(
      TestDiagnosticMessages diagnostics, ClassReference referencedFrom) {
    inspectDiagnosticsWithNoRules(
        diagnostics,
        getExpectedDiagnosticMessageWithNoRules(referencedFrom, ClassReference::getTypeName));
  }

  void inspectDiagnosticsWithNoRules(
      TestDiagnosticMessages diagnostics, FieldReference referencedFrom) {
    inspectDiagnosticsWithNoRules(
        diagnostics,
        getExpectedDiagnosticMessageWithNoRules(
            referencedFrom, FieldReferenceUtils::toSourceString));
  }

  void inspectDiagnosticsWithNoRules(
      TestDiagnosticMessages diagnostics, MethodReference referencedFrom) {
    inspectDiagnosticsWithNoRules(
        diagnostics,
        getExpectedDiagnosticMessageWithNoRules(
            referencedFrom, MethodReferenceUtils::toSourceString));
  }

  void inspectDiagnosticsWithNoRules(
      TestDiagnosticMessages diagnostics, String expectedDiagnosticMessage) {
    MissingDefinitionsDiagnosticImpl diagnostic =
        diagnostics
            .assertOnlyErrors()
            .assertErrorsCount(1)
            .assertAllErrorsMatch(diagnosticType(MissingDefinitionsDiagnosticImpl.class))
            .getError(0);
    assertEquals(ImmutableSet.of(getMissingClassReference()), diagnostic.getMissingClasses());
    assertEquals(expectedDiagnosticMessage, diagnostic.getDiagnosticMessage());
  }

  private <T> String getExpectedDiagnosticMessageWithNoRules(
      T referencedFrom, Function<T, String> toSourceStringFunction) {
    return "Compilation can't be completed because the following class is missing: "
        + getMissingClassReference().getTypeName()
        + " (referenced from: "
        + toSourceStringFunction.apply(referencedFrom)
        + ")"
        + ".";
  }
}