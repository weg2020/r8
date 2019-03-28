// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.D8Command.Builder;
import com.android.tools.r8.TestBase.Backend;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.InternalOptions;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class D8TestBuilder
    extends TestCompilerBuilder<
        D8Command, Builder, D8TestCompileResult, D8TestRunResult, D8TestBuilder> {

  private D8TestBuilder(TestState state, Builder builder) {
    super(state, builder, Backend.DEX);
  }

  public static D8TestBuilder create(TestState state) {
    return new D8TestBuilder(state, D8Command.builder(state.getDiagnosticsHandler()));
  }

  @Override
  D8TestBuilder self() {
    return this;
  }

  @Override
  D8TestCompileResult internalCompile(
      Builder builder, Consumer<InternalOptions> optionsConsumer, Supplier<AndroidApp> app)
      throws CompilationFailedException {
    ToolHelper.runD8(builder, optionsConsumer);
    return new D8TestCompileResult(getState(), app.get());
  }

  public D8TestBuilder setIntermediate(boolean intermediate) {
    builder.setIntermediate(intermediate);
    return self();
  }
}
