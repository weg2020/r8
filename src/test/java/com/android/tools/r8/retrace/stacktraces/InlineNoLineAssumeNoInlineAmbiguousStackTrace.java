// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.retrace.stacktraces;

import com.android.tools.r8.utils.StringUtils;
import java.util.Arrays;
import java.util.List;

public class InlineNoLineAssumeNoInlineAmbiguousStackTrace implements StackTraceForTest {

  @Override
  public List<String> obfuscatedStackTrace() {
    return Arrays.asList(
        "Exception in thread \"main\" java.lang.NullPointerException",
        "\tat a.foo(Unknown Source)");
  }

  @Override
  public List<String> retracedStackTrace() {
    return Arrays.asList(
        "Exception in thread \"main\" java.lang.NullPointerException",
        // TODO(b/231622686): Should not emit inline frame.
        "\tat retrace.Main.method1(Main.java)",
        "\tat retrace.Main.main(Main.java)",
        "\t<OR> at retrace.Main.otherMain(Main.java)");
  }

  @Override
  public List<String> retraceVerboseStackTrace() {
    return Arrays.asList(
        "Exception in thread \"main\" java.lang.NullPointerException",
        // TODO(b/231622686): Should not emit inline frame.
        "\tat retrace.Main.void method1(java.lang.String)(Main.java:0)",
        "\tat retrace.Main.void main(java.lang.String[])(Main.java:0)",
        "\t<OR> at retrace.Main.void otherMain(java.lang.String[])(Main.java)");
  }

  @Override
  public String mapping() {
    return StringUtils.lines(
        "retrace.Main -> a:",
        "    void otherMain(java.lang.String[]) -> foo",
        "    2:2:void method1(java.lang.String):0:0 -> foo",
        "    2:2:void main(java.lang.String[]):0 -> foo");
  }

  @Override
  public int expectedWarnings() {
    return 0;
  }
}
