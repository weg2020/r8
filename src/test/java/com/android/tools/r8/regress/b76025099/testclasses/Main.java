// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.regress.b76025099.testclasses;

import com.android.tools.r8.regress.b76025099.testclasses.impl.Factory;

public class Main {
  public static void main(String[] args) {
    Logger l = Factory.getImpl(Main.class.getCanonicalName());
    System.out.println(l.getName());
  }
}
