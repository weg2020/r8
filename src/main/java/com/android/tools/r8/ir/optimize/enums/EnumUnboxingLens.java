// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.enums;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.NestedGraphLens;
import com.android.tools.r8.graph.RewrittenPrototypeDescription;
import com.android.tools.r8.ir.code.Invoke;
import com.android.tools.r8.utils.BooleanUtils;
import com.android.tools.r8.utils.collections.BidirectionalOneToOneHashMap;
import com.android.tools.r8.utils.collections.BidirectionalOneToOneMap;
import com.android.tools.r8.utils.collections.MutableBidirectionalOneToOneMap;
import com.google.common.collect.ImmutableMap;
import java.util.IdentityHashMap;
import java.util.Map;

class EnumUnboxingLens extends NestedGraphLens {

  private final Map<DexMethod, RewrittenPrototypeDescription> prototypeChangesPerMethod;

  EnumUnboxingLens(
      AppView<?> appView,
      BidirectionalOneToOneMap<DexField, DexField> fieldMap,
      BidirectionalOneToOneMap<DexMethod, DexMethod> methodMap,
      Map<DexType, DexType> typeMap,
      Map<DexMethod, RewrittenPrototypeDescription> prototypeChangesPerMethod) {
    super(appView, fieldMap, methodMap, typeMap);
    this.prototypeChangesPerMethod = prototypeChangesPerMethod;
  }

  @Override
  protected RewrittenPrototypeDescription internalDescribePrototypeChanges(
      RewrittenPrototypeDescription prototypeChanges, DexMethod method) {
    // During the second IR processing enum unboxing is the only optimization rewriting
    // prototype description, if this does not hold, remove the assertion and merge
    // the two prototype changes.
    assert prototypeChanges.isEmpty();
    return prototypeChangesPerMethod.getOrDefault(method, RewrittenPrototypeDescription.none());
  }

  @Override
  protected Invoke.Type mapInvocationType(
      DexMethod newMethod, DexMethod originalMethod, Invoke.Type type) {
    if (typeMap.containsKey(originalMethod.getHolderType())) {
      // Methods moved from unboxed enums to the utility class are either static or statified.
      assert newMethod != originalMethod;
      return Invoke.Type.STATIC;
    }
    return type;
  }

  public static Builder enumUnboxingLensBuilder() {
    return new Builder();
  }

  static class Builder {

    protected final Map<DexType, DexType> typeMap = new IdentityHashMap<>();
    protected final MutableBidirectionalOneToOneMap<DexField, DexField> newFieldSignatures =
        new BidirectionalOneToOneHashMap<>();
    protected final MutableBidirectionalOneToOneMap<DexMethod, DexMethod> newMethodSignatures =
        new BidirectionalOneToOneHashMap<>();

    private Map<DexMethod, RewrittenPrototypeDescription> prototypeChangesPerMethod =
        new IdentityHashMap<>();

    public void map(DexType from, DexType to) {
      if (from == to) {
        return;
      }
      typeMap.put(from, to);
    }

    public void move(DexField from, DexField to) {
      if (from == to) {
        return;
      }
      newFieldSignatures.put(from, to);
    }

    public void move(DexMethod from, DexMethod to, boolean fromStatic, boolean toStatic) {
      move(from, to, fromStatic, toStatic, 0);
    }

    public void move(
        DexMethod from,
        DexMethod to,
        boolean fromStatic,
        boolean toStatic,
        int numberOfExtraNullParameters) {
      assert from != to;
      newMethodSignatures.put(from, to);
      int offsetDiff = 0;
      int toOffset = BooleanUtils.intValue(!toStatic);
      RewrittenPrototypeDescription.ArgumentInfoCollection.Builder builder =
          RewrittenPrototypeDescription.ArgumentInfoCollection.builder();
      if (fromStatic != toStatic) {
        assert toStatic;
        offsetDiff = 1;
        builder.addArgumentInfo(
            0,
            new RewrittenPrototypeDescription.RewrittenTypeInfo(
                from.holder, to.proto.parameters.values[0]));
      }
      for (int i = 0; i < from.proto.parameters.size(); i++) {
        DexType fromType = from.proto.parameters.values[i];
        DexType toType = to.proto.parameters.values[i + offsetDiff];
        if (fromType != toType) {
          builder.addArgumentInfo(
              i + offsetDiff + toOffset,
              new RewrittenPrototypeDescription.RewrittenTypeInfo(fromType, toType));
        }
      }
      RewrittenPrototypeDescription.RewrittenTypeInfo returnInfo =
          from.proto.returnType == to.proto.returnType
              ? null
              : new RewrittenPrototypeDescription.RewrittenTypeInfo(
                  from.proto.returnType, to.proto.returnType);
      prototypeChangesPerMethod.put(
          to,
          RewrittenPrototypeDescription.createForRewrittenTypes(returnInfo, builder.build())
              .withExtraUnusedNullParameters(numberOfExtraNullParameters));
    }

    public EnumUnboxingLens build(AppView<?> appView) {
      assert !typeMap.isEmpty();
      return new EnumUnboxingLens(
          appView,
          newFieldSignatures,
          newMethodSignatures,
          typeMap,
          ImmutableMap.copyOf(prototypeChangesPerMethod));
    }
  }
}
