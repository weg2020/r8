// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.classinliner;

import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InvokeMethod;
import com.android.tools.r8.ir.optimize.Inliner.InliningInfo;
import com.android.tools.r8.ir.optimize.InliningOracle;
import com.android.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.google.common.collect.Streams;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class ClassInliner {
  private final DexItemFactory factory;
  private final int totalMethodInstructionLimit;
  private final ConcurrentHashMap<DexType, Boolean> knownClasses = new ConcurrentHashMap<>();

  public interface InlinerAction {
    void inline(Map<InvokeMethod, InliningInfo> methods);
  }

  public ClassInliner(DexItemFactory factory, int totalMethodInstructionLimit) {
    this.factory = factory;
    this.totalMethodInstructionLimit = totalMethodInstructionLimit;
  }

  // Process method code and inline eligible class instantiations, in short:
  //
  // - collect all 'new-instance' and 'static-get' instructions (called roots below) in
  // the original code. Note that class inlining, if happens, mutates code and may add
  // new root instructions. Processing them as well is possible, but does not seem to
  // bring much value.
  //
  // - for each 'new-instance' root we check if it is eligible for inlining, i.e:
  //     -> the class of the new instance is 'eligible' (see computeClassEligible(...))
  //     -> the instance is initialized with 'eligible' constructor (see comments in
  //        CodeRewriter::identifyClassInlinerEligibility(...))
  //     -> has only 'eligible' uses, i.e:
  //          * as a receiver of a field read/write for a field defined in same class
  //            as method.
  //          * as a receiver of virtual or interface call with single target being
  //            an eligible method according to identifyClassInlinerEligibility(...);
  //            NOTE: if method receiver is used as a return value, the method call
  //            should ignore return value
  //
  // - for each 'static-get' root we check if it is eligible for inlining, i.e:
  //     -> the class of the new instance is 'eligible' (see computeClassEligible(...))
  //        *and* has a trivial class constructor (see CoreRewriter::computeClassInitializerInfo
  //        and description in isClassAndUsageEligible(...)) initializing the field root reads
  //     -> has only 'eligible' uses, (see above)
  //
  // - inline eligible root instructions, i.e:
  //     -> force inline methods called on the instance (including the initializer);
  //        (this may introduce additional instance field reads/writes on the receiver)
  //     -> replace instance field reads with appropriate values calculated based on
  //        fields writes
  //     -> remove the call to superclass initializer (if root is 'new-instance')
  //     -> remove all field writes
  //     -> remove root instructions
  //
  // For example:
  //
  // Original code:
  //   class C {
  //     static class L {
  //       final int x;
  //       L(int x) {
  //         this.x = x;
  //       }
  //       int getX() {
  //         return x;
  //       }
  //     }
  //     static class F {
  //       final static F I = new F();
  //       int getX() {
  //         return 123;
  //       }
  //     }
  //     static int method1() {
  //       return new L(1).x;
  //     }
  //     static int method2() {
  //       return new L(1).getX();
  //     }
  //     static int method3() {
  //       return F.I.getX();
  //     }
  //   }
  //
  // Code after class C is 'inlined':
  //   class C {
  //     static int method1() {
  //       return 1;
  //     }
  //     static int method2() {
  //       return 1;
  //     }
  //     static int method3() {
  //       return "F::getX";
  //     }
  //   }
  //
  public final void processMethodCode(
      AppInfoWithLiveness appInfo,
      DexEncodedMethod method,
      IRCode code,
      Predicate<DexEncodedMethod> isProcessedConcurrently,
      InlinerAction inliner,
      Supplier<InliningOracle> defaultOracle) {

    // Collect all the new-instance and static-get instructions in the code before inlining.
    List<Instruction> roots = Streams.stream(code.instructionIterator())
        .filter(insn -> insn.isNewInstance() || insn.isStaticGet())
        .collect(Collectors.toList());

    for (Instruction root : roots) {
      InlineCandidateProcessor processor =
          new InlineCandidateProcessor(factory, appInfo,
              type -> isClassEligible(appInfo, type),
              isProcessedConcurrently, method, root);

      // Assess eligibility and compute inlining of direct calls and extra methods needed.
      if (!processor.isInstanceEligible() ||
          !processor.isClassAndUsageEligible() ||
          !processor.areInstanceUsersEligible(defaultOracle)) {
        continue;
      }

      // Is inlining allowed.
      if (processor.getEstimatedCombinedSizeForInlining() >= totalMethodInstructionLimit) {
        continue;
      }

      // Inline the class instance.
      processor.processInlining(code, inliner);

      // Restore normality.
      code.removeAllTrivialPhis();
      assert code.isConsistentSSA();
    }
  }

  private boolean isClassEligible(AppInfo appInfo, DexType clazz) {
    Boolean eligible = knownClasses.get(clazz);
    if (eligible == null) {
      Boolean computed = computeClassEligible(appInfo, clazz);
      Boolean existing = knownClasses.putIfAbsent(clazz, computed);
      assert existing == null || existing == computed;
      eligible = existing == null ? computed : existing;
    }
    return eligible;
  }

  // Class is eligible for this optimization. Eligibility implementation:
  //   - is not an abstract class or interface
  //   - directly extends java.lang.Object
  //   - does not declare finalizer
  //   - does not trigger any static initializers except for its own
  private boolean computeClassEligible(AppInfo appInfo, DexType clazz) {
    DexClass definition = appInfo.definitionFor(clazz);
    if (definition == null || definition.isLibraryClass() ||
        definition.accessFlags.isAbstract() || definition.accessFlags.isInterface()) {
      return false;
    }

    // Must directly extend Object.
    if (definition.superType != factory.objectType) {
      return false;
    }

    // Class must not define finalizer.
    for (DexEncodedMethod method : definition.virtualMethods()) {
      if (method.method.name == factory.finalizeMethodName &&
          method.method.proto == factory.objectMethods.finalize.proto) {
        return false;
      }
    }

    // Check for static initializers in this class or any of interfaces it implements.
    return !appInfo.canTriggerStaticInitializer(clazz, true);
  }
}
