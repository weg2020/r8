// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.horizontalclassmerging.policies;

import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.horizontalclassmerging.SingleClassPolicy;

public class NoInnerClasses extends SingleClassPolicy {

  @Override
  public boolean canMerge(DexProgramClass program) {
    // TODO(b/179018501): allow merging classes with inner/outer classes.
    return program.getInnerClasses().isEmpty();
  }

  @Override
  public String getName() {
    return "NoInnerClasses";
  }
}
