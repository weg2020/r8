// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.classinliner;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

import com.android.tools.r8.NeverInline;
import com.android.tools.r8.NoVerticalClassMerging;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ClassInlineInstanceInitializerWithIfTest extends TestBase {

  private final TestParameters parameters;

  @Parameterized.Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimesAndApiLevels().build();
  }

  public ClassInlineInstanceInitializerWithIfTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void test() throws Exception {
    testForR8(parameters.getBackend())
        .addInnerClasses(ClassInlineInstanceInitializerWithIfTest.class)
        .addKeepMainRule(TestClass.class)
        .enableInliningAnnotations()
        .enableNoVerticalClassMergingAnnotations()
        .setMinApi(parameters.getApiLevel())
        .compile()
        .inspect(this::inspect)
        .run(parameters.getRuntime(), TestClass.class)
        .assertSuccessWithOutputLines("Hello world!");
  }

  private void inspect(CodeInspector inspector) {
    assertThat(inspector.clazz(Candidate.class), not(isPresent()));
    assertThat(inspector.clazz(CandidateBase.class), not(isPresent()));
  }

  static class TestClass {

    public static void main(String[] args) {
      System.out.print(new Candidate(true).get());
      System.out.println(new Candidate(false).get());
    }
  }

  @NoVerticalClassMerging
  static class CandidateBase {

    final String f;

    CandidateBase(boolean b) {
      if (b) {
        f = "Hello";
      } else {
        f = " world!";
      }
    }
  }

  static class Candidate extends CandidateBase {

    Candidate(boolean b) {
      super(b);
    }

    @NeverInline
    String get() {
      return f;
    }
  }
}
