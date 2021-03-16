// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.classmerging.horizontal;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsNot.not;

import com.android.tools.r8.NeverClassInline;
import com.android.tools.r8.NoHorizontalClassMerging;
import com.android.tools.r8.OutputMode;
import com.android.tools.r8.R8TestCompileResult;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import java.nio.file.Path;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class PreventMergeMainDexTracingTest extends HorizontalClassMergingTestBase {
  public PreventMergeMainDexTracingTest(TestParameters parameters) {
    super(parameters);
  }

  @Parameterized.Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters()
        .withDexRuntimes()
        .withApiLevelsEndingAtExcluding(apiLevelWithNativeMultiDexSupport())
        .build();
  }

  @Test
  public void testR8() throws Exception {
    testForR8(parameters.getBackend())
        .addInnerClasses(getClass())
        .addKeepMainRule(Main.class)
        .addKeepClassAndMembersRules(Other.class)
        .addMainDexKeepClassRules(Main.class)
        .addOptionsModification(options -> options.minimalMainDex = true)
        .enableNeverClassInliningAnnotations()
        .enableNoHorizontalClassMergingAnnotations()
        .setMinApi(parameters.getApiLevel())
        .compile()
        .apply(this::checkCompileResult)
        .run(parameters.getRuntime(), Main.class)
        .assertSuccessWithOutputLines("main dex");
  }

  private void checkCompileResult(R8TestCompileResult compileResult) throws Exception {
    Path out = temp.newFolder().toPath();
    compileResult.app.writeToDirectory(out, OutputMode.DexIndexed);
    Path classes = out.resolve("classes.dex");
    Path classes2 = out.resolve("classes2.dex");
    inspectMainDex(new CodeInspector(classes, compileResult.getProguardMap()));
    inspectSecondaryDex(new CodeInspector(classes2, compileResult.getProguardMap()));
  }

  private void inspectMainDex(CodeInspector inspector) {
    assertThat(inspector.clazz(A.class), isPresent());
    assertThat(inspector.clazz(B.class), not(isPresent()));
  }

  private void inspectSecondaryDex(CodeInspector inspector) {
    assertThat(inspector.clazz(A.class), not(isPresent()));
    assertThat(inspector.clazz(B.class), isPresent());
  }

  public static class Main {
    public static void main(String[] args) {
      A a = new A();
    }
  }

  @NoHorizontalClassMerging
  public static class Other {
    public static void otherDex() {
      B b = new B();
    }
  }

  @NeverClassInline
  public static class A {
    public A() {
      System.out.println("main dex");
    }
  }

  @NeverClassInline
  public static class B {
    public B() {
      System.out.println("not main dex");
    }
  }
}
