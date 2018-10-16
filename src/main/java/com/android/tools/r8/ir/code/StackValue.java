// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.analysis.type.TypeLatticeElement;

public class StackValue extends Value {

  private final int height;
  private final DexType objectType;

  private StackValue(DexType objectType, TypeLatticeElement typeLattice, int height) {
    super(Value.UNDEFINED_NUMBER, typeLattice, null);
    this.height = height;
    this.objectType = objectType;
    assert height >= 0;
  }

  public static StackValue forObjectType(DexType type, int height) {
    assert DexItemFactory.nullValueType == type || type.isClassType() || type.isArrayType();
    return new StackValue(type, TypeLatticeElement.fromDexType(type), height);
  }

  public static StackValue forNonObjectType(ValueType valueType, int height) {
    assert valueType.isPreciseType() && !valueType.isObject();
    return new StackValue(null, valueType.toTypeLattice(), height);
  }

  public int getHeight() {
    return height;
  }

  public DexType getObjectType() {
    assert outType().isObject();
    return objectType;
  }

  public StackValue duplicate(int height) {
    return new StackValue(this.objectType, this.type.toTypeLattice(), height);
  }

  @Override
  public boolean needsRegister() {
    return false;
  }

  @Override
  public void setNeedsRegister(boolean value) {
    assert !value;
  }

  @Override
  public String toString() {
    return "s" + height;
  }
}
