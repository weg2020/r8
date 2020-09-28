#!/usr/bin/env python
# Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
# for details. All rights reserved. Use of this source code is governed by a
# BSD-style license that can be found in the LICENSE file.

import os
import shutil
from string import Template

OUTPUT_DIR = os.path.join('build', 'generated', 'test', 'java', 'com',
                          'android', 'tools', 'r8', 'art')
TEST_DIR = os.path.join('tests', '2017-10-04', 'art')
TOOLCHAINS = [
    ("dx", os.path.join(TEST_DIR, "dx")),
    ("none", os.path.join(TEST_DIR, "dx")),
]
TOOLS = ["r8", "d8", "r8cf"]
TEMPLATE = Template(
"""// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.art.$testGeneratingToolchain.$compilerUnderTest;

import static com.android.tools.r8.R8RunArtTestsTest.DexTool.$testGeneratingToolchainEnum;

import com.android.tools.r8.R8RunArtTestsTest;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.ToolHelper.DexVm;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Auto-generated test for the art $name test using the $testGeneratingToolchain toolchain.
 *
 * DO NOT EDIT THIS FILE. EDIT THE HERE DOCUMENT TEMPLATE IN tools/create_art_tests.py INSTEAD!
 */
@RunWith(Parameterized.class)
public class $testClassName extends R8RunArtTestsTest {

    @Parameters(name = "{0}")
    public static TestParametersCollection data() {
      return TestBase.getTestParameters().withDexRuntimes().build();
    }

    private final TestParameters parameters;

    public $testClassName(TestParameters parameters) {
      super("$name", $testGeneratingToolchainEnum);
      this.parameters = parameters;
    }

    @Test
    public void test() throws Throwable {
      runArtTest(parameters.getRuntime().asDex().getVm(), CompilerUnderTest.$compilerUnderTestEnum);
    }
}
""")


def get_test_configurations():
  for toolchain, source_dir in TOOLCHAINS:
    for tool in TOOLS:
      if tool == "d8" and toolchain == "none":
        tool_enum = 'R8_AFTER_D8'
      else:
        tool_enum = tool.upper()
      if tool == "r8cf":
        if toolchain != "none":
          continue
        tool_enum = 'D8_AFTER_R8CF'
      output_dir = os.path.join(OUTPUT_DIR, toolchain, tool)
      yield (tool_enum, tool, toolchain, source_dir, output_dir)


def create_tests():
  for tool_enum, tool, toolchain, source_dir, output_dir in get_test_configurations():
    test_cases = [d for d in os.listdir(source_dir)
                  if os.path.isdir(os.path.join(source_dir, d))]
    if os.path.exists(output_dir):
      shutil.rmtree(output_dir)
    os.makedirs(output_dir)
    for test_case in test_cases:
      class_name = "Art" + test_case.replace("-", "_") + "Test"
      contents = TEMPLATE.substitute(
          name=test_case,
          compilerUnderTestEnum=tool_enum,
          compilerUnderTest=tool,
          testGeneratingToolchain=toolchain,
          testGeneratingToolchainEnum=toolchain.upper(),
          testClassName=class_name)
      with open(os.path.join(output_dir, class_name + ".java"), "w") as fp:
        fp.write(contents)


def main():
  create_tests()


if __name__ == "__main__":
  main()
