// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.desugar.desugaredlibrary.jdktests;

import com.android.tools.r8.TestParameters;
import com.android.tools.r8.ToolHelper.DexVm.Version;
import com.android.tools.r8.desugar.desugaredlibrary.test.CompilationSpecification;
import com.android.tools.r8.desugar.desugaredlibrary.test.LibraryDesugaringSpecification;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class Jdk11TimeFormatChronoTests extends Jdk11TimeAbstractTests {

  public Jdk11TimeFormatChronoTests(
      TestParameters parameters,
      LibraryDesugaringSpecification libraryDesugaringSpecification,
      CompilationSpecification compilationSpecification) {
    super(parameters, libraryDesugaringSpecification, compilationSpecification);
  }

  @Test
  public void testTime() throws Exception {
    testTime(FORMAT_CHRONO_SUCCESSES);
    if (parameters.getDexRuntimeVersion().isOlderThan(Version.V12_0_0)) {
      // Formatting issues starting from 12.
      testTime(FORMAT_CHRONO_SUCCESSES_UP_TO_11);
    }
  }
}
