// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.graph;

import com.android.tools.r8.utils.ConsumerUtils;
import com.android.tools.r8.utils.MapUtils;
import com.android.tools.r8.utils.collections.ProgramMethodSet;
import com.google.common.collect.Sets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class MethodAccessInfoCollection {

  private final Map<DexMethod, ProgramMethodSet> directInvokes;
  private final Map<DexMethod, ProgramMethodSet> interfaceInvokes;
  private final Map<DexMethod, ProgramMethodSet> staticInvokes;
  private final Map<DexMethod, ProgramMethodSet> superInvokes;
  private final Map<DexMethod, ProgramMethodSet> virtualInvokes;

  private MethodAccessInfoCollection(
      Map<DexMethod, ProgramMethodSet> directInvokes,
      Map<DexMethod, ProgramMethodSet> interfaceInvokes,
      Map<DexMethod, ProgramMethodSet> staticInvokes,
      Map<DexMethod, ProgramMethodSet> superInvokes,
      Map<DexMethod, ProgramMethodSet> virtualInvokes) {
    this.directInvokes = directInvokes;
    this.interfaceInvokes = interfaceInvokes;
    this.staticInvokes = staticInvokes;
    this.superInvokes = superInvokes;
    this.virtualInvokes = virtualInvokes;
  }

  public static ConcurrentBuilder concurrentBuilder() {
    return new ConcurrentBuilder();
  }

  // TODO(b/132593519): We should not need sorted maps with the new member rebinding analysis.
  public static SortedBuilder sortedBuilder() {
    return new SortedBuilder();
  }

  public void forEachMethodReference(Consumer<DexMethod> method) {
    Set<DexMethod> seen = Sets.newIdentityHashSet();
    directInvokes.keySet().forEach(ConsumerUtils.acceptIfNotSeen(method, seen));
    interfaceInvokes.keySet().forEach(ConsumerUtils.acceptIfNotSeen(method, seen));
    staticInvokes.keySet().forEach(ConsumerUtils.acceptIfNotSeen(method, seen));
    superInvokes.keySet().forEach(ConsumerUtils.acceptIfNotSeen(method, seen));
    virtualInvokes.keySet().forEach(ConsumerUtils.acceptIfNotSeen(method, seen));
  }

  public void forEachDirectInvoke(BiConsumer<DexMethod, ProgramMethodSet> consumer) {
    directInvokes.forEach(consumer);
  }

  public void forEachInterfaceInvoke(BiConsumer<DexMethod, ProgramMethodSet> consumer) {
    interfaceInvokes.forEach(consumer);
  }

  public void forEachStaticInvoke(BiConsumer<DexMethod, ProgramMethodSet> consumer) {
    staticInvokes.forEach(consumer);
  }

  public void forEachSuperInvoke(BiConsumer<DexMethod, ProgramMethodSet> consumer) {
    superInvokes.forEach(consumer);
  }

  public void forEachVirtualInvoke(BiConsumer<DexMethod, ProgramMethodSet> consumer) {
    virtualInvokes.forEach(consumer);
  }

  public MethodAccessInfoCollection rewrittenWithLens(
      DexDefinitionSupplier definitions, GraphLens lens) {
    return new MethodAccessInfoCollection(
        rewriteInvokesWithLens(directInvokes, definitions, lens),
        rewriteInvokesWithLens(interfaceInvokes, definitions, lens),
        rewriteInvokesWithLens(staticInvokes, definitions, lens),
        rewriteInvokesWithLens(superInvokes, definitions, lens),
        rewriteInvokesWithLens(virtualInvokes, definitions, lens));
  }

  private static Map<DexMethod, ProgramMethodSet> rewriteInvokesWithLens(
      Map<DexMethod, ProgramMethodSet> invokes, DexDefinitionSupplier definitions, GraphLens lens) {
    return MapUtils.map(
        invokes,
        capacity -> new TreeMap<>(DexMethod::slowCompareTo),
        lens::getRenamedMethodSignature,
        methods -> methods.rewrittenWithLens(definitions, lens),
        (methods, other) -> {
          methods.addAll(other);
          return methods;
        });
  }

  public abstract static class Builder<T extends Map<DexMethod, ProgramMethodSet>> {

    private final T directInvokes;
    private final T interfaceInvokes;
    private final T staticInvokes;
    private final T superInvokes;
    private final T virtualInvokes;

    private Builder(Supplier<T> factory) {
      directInvokes = factory.get();
      interfaceInvokes = factory.get();
      staticInvokes = factory.get();
      superInvokes = factory.get();
      virtualInvokes = factory.get();
    }

    public T getDirectInvokes() {
      return directInvokes;
    }

    public T getInterfaceInvokes() {
      return interfaceInvokes;
    }

    public T getStaticInvokes() {
      return staticInvokes;
    }

    public T getSuperInvokes() {
      return superInvokes;
    }

    public T getVirtualInvokes() {
      return virtualInvokes;
    }

    public boolean registerInvokeDirectInContext(DexMethod invokedMethod, ProgramMethod context) {
      return registerInvokeMethodInContext(invokedMethod, context, directInvokes);
    }

    public boolean registerInvokeInterfaceInContext(
        DexMethod invokedMethod, ProgramMethod context) {
      return registerInvokeMethodInContext(invokedMethod, context, interfaceInvokes);
    }

    public boolean registerInvokeStaticInContext(DexMethod invokedMethod, ProgramMethod context) {
      return registerInvokeMethodInContext(invokedMethod, context, staticInvokes);
    }

    public boolean registerInvokeSuperInContext(DexMethod invokedMethod, ProgramMethod context) {
      return registerInvokeMethodInContext(invokedMethod, context, superInvokes);
    }

    public boolean registerInvokeVirtualInContext(DexMethod invokedMethod, ProgramMethod context) {
      return registerInvokeMethodInContext(invokedMethod, context, virtualInvokes);
    }

    private static boolean registerInvokeMethodInContext(
        DexMethod invokedMethod, ProgramMethod context, Map<DexMethod, ProgramMethodSet> invokes) {
      return invokes
          .computeIfAbsent(invokedMethod, ignore -> ProgramMethodSet.create())
          .add(context);
    }

    public MethodAccessInfoCollection build() {
      return new MethodAccessInfoCollection(
          directInvokes, interfaceInvokes, staticInvokes, superInvokes, virtualInvokes);
    }
  }

  public static class ConcurrentBuilder
      extends Builder<ConcurrentHashMap<DexMethod, ProgramMethodSet>> {

    private ConcurrentBuilder() {
      super(ConcurrentHashMap::new);
    }
  }

  public static class SortedBuilder extends Builder<TreeMap<DexMethod, ProgramMethodSet>> {

    private SortedBuilder() {
      super(() -> new TreeMap<>(DexMethod::slowCompareTo));
    }
  }
}