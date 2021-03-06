// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.missingclasses;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.TestDiagnosticMessages;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.diagnostic.MissingDefinitionContext;
import com.android.tools.r8.diagnostic.internal.MissingDefinitionMethodContextImpl;
import com.android.tools.r8.utils.MethodReferenceUtils;
import org.junit.Test;

/**
 * If a method reference that refers to a missing class resolves to a definition, then the method
 * definition is to be blamed, and not the enclosing method.
 */
public class MissingClassReferencedFromInvokeVirtualToPresentMethodParameterTest
    extends MissingClassesTestBase {

  private static final MissingDefinitionContext referencedFrom =
      MissingDefinitionMethodContextImpl.builder()
          .setMethodContext(
              MethodReferenceUtils.methodFromMethod(Main.class, "get", MissingClass.class))
          .setOrigin(getOrigin(Main.class))
          .build();

  public MissingClassReferencedFromInvokeVirtualToPresentMethodParameterTest(
      TestParameters parameters) {
    super(parameters);
  }

  @Test(expected = CompilationFailedException.class)
  public void testNoRules() throws Exception {
    compileWithExpectedDiagnostics(
        Main.class, diagnostics -> inspectDiagnosticsWithNoRules(diagnostics, referencedFrom));
  }

  @Test
  public void testDontWarnMainClass() throws Exception {
    compileWithExpectedDiagnostics(
        Main.class, TestDiagnosticMessages::assertNoMessages, addDontWarn(Main.class));
  }

  @Test
  public void testDontWarnMissingClass() throws Exception {
    compileWithExpectedDiagnostics(
        Main.class, TestDiagnosticMessages::assertNoMessages, addDontWarn(MissingClass.class));
  }

  @Test
  public void testIgnoreWarnings() throws Exception {
    compileWithExpectedDiagnostics(
        Main.class,
        diagnostics -> inspectDiagnosticsWithIgnoreWarnings(diagnostics, referencedFrom),
        addIgnoreWarnings());
  }

  static class Main {

    public static void main(String[] args) {
      new Main().get(null);
    }

    public void get(MissingClass mc) {}
  }
}
