// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.classinliner;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexEncodedMethod.ClassInlinerEligibility;
import com.android.tools.r8.graph.DexEncodedMethod.OptimizationInfo;
import com.android.tools.r8.graph.DexEncodedMethod.ParameterUsagesInfo.NotUsed;
import com.android.tools.r8.graph.DexEncodedMethod.ParameterUsagesInfo.ParameterUsage;
import com.android.tools.r8.graph.DexEncodedMethod.ParameterUsagesInfo.SingleCallOfArgumentMethod;
import com.android.tools.r8.graph.DexEncodedMethod.TrivialInitializer;
import com.android.tools.r8.graph.DexEncodedMethod.TrivialInitializer.TrivialClassInitializer;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.ConstNumber;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.InstanceGet;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.Invoke.Type;
import com.android.tools.r8.ir.code.InvokeDirect;
import com.android.tools.r8.ir.code.InvokeMethod;
import com.android.tools.r8.ir.code.InvokeMethodWithReceiver;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.optimize.Inliner.InlineAction;
import com.android.tools.r8.ir.optimize.Inliner.InliningInfo;
import com.android.tools.r8.ir.optimize.Inliner.Reason;
import com.android.tools.r8.ir.optimize.InliningOracle;
import com.android.tools.r8.ir.optimize.classinliner.ClassInliner.InlinerAction;
import com.android.tools.r8.kotlin.KotlinInfo;
import com.android.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.android.tools.r8.utils.Pair;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class InlineCandidateProcessor {
  private final DexItemFactory factory;
  private final AppInfoWithLiveness appInfo;
  private final Predicate<DexType> isClassEligible;
  private final Predicate<DexEncodedMethod> isProcessedConcurrently;
  private final DexEncodedMethod method;
  private final Instruction root;

  private Value eligibleInstance;
  private DexType eligibleClass;
  private DexClass eligibleClassDefinition;

  private final Map<InvokeMethod, InliningInfo> methodCallsOnInstance
      = new IdentityHashMap<>();
  private final Map<InvokeMethod, InliningInfo> extraMethodCalls
      = new IdentityHashMap<>();
  private final List<Pair<InvokeMethod, Integer>> unusedArguments
      = new ArrayList<>();

  private int estimatedCombinedSizeForInlining = 0;

  InlineCandidateProcessor(
      DexItemFactory factory, AppInfoWithLiveness appInfo,
      Predicate<DexType> isClassEligible,
      Predicate<DexEncodedMethod> isProcessedConcurrently,
      DexEncodedMethod method, Instruction root) {
    this.factory = factory;
    this.isClassEligible = isClassEligible;
    this.method = method;
    this.root = root;
    this.appInfo = appInfo;
    this.isProcessedConcurrently = isProcessedConcurrently;
  }

  int getEstimatedCombinedSizeForInlining() {
    return estimatedCombinedSizeForInlining;
  }

  // Checks if the root instruction defines eligible value, i.e. the value
  // exists and we have a definition of its class.
  boolean isInstanceEligible() {
    eligibleInstance = root.outValue();
    if (eligibleInstance == null) {
      return false;
    }

    eligibleClass = isNewInstance() ?
        root.asNewInstance().clazz : root.asStaticGet().getField().type;
    eligibleClassDefinition = appInfo.definitionFor(eligibleClass);
    return eligibleClassDefinition != null;
  }

  // Checks if the class is eligible and is properly used. Regarding general class
  // eligibility rules see comment on computeClassEligible(...).
  //
  // In addition to class being eligible this method also checks:
  //   -- for 'new-instance' root:
  //      * class itself does not have static initializer
  //   -- for 'static-get' root:
  //      * class does not have instance fields
  //      * class is final
  //      * class has class initializer marked as TrivialClassInitializer, and
  //        class initializer initializes the field we are reading here.
  boolean isClassAndUsageEligible() {
    if (!isClassEligible.test(eligibleClass)) {
      return false;
    }

    if (isNewInstance()) {
      // There must be no static initializer on the class itself.
      return !eligibleClassDefinition.hasClassInitializer();
    }

    assert root.isStaticGet();

    // Checking if we can safely inline class implemented following singleton-like
    // pattern, by which we assume a static final field holding on to the reference
    // initialized in class constructor.
    //
    // In general we are targeting cases when the class is defined as:
    //
    //   class X {
    //     static final X F;
    //     static {
    //       F = new X();
    //     }
    //   }
    //
    // and being used as follows:
    //
    //   void foo() {
    //     f = X.F;
    //     f.bar();
    //   }
    //
    // The main difference from the similar case of class inliner with 'new-instance'
    // instruction is that in this case the instance we inline is not just leaked, but
    // is actually published via X.F field. There are several risks we need to address
    // in this case:
    //
    //    Risk: instance stored in field X.F has changed after it was initialized in
    //      class initializer
    //    Solution: we assume that final field X.F is not modified outside the class
    //      initializer. In rare cases when it is (e.g. via reflections) it should
    //      be marked with keep rules
    //
    //    Risk: instance stored in field X.F is not initialized yet
    //    Solution: not initialized instance can only be visible if X.<clinit>
    //      triggers other class initialization which references X.F. This
    //      situation should never happen if we:
    //        -- don't allow any superclasses to have static initializer,
    //        -- don't allow any subclasses,
    //        -- guarantee the class has trivial class initializer
    //           (see CodeRewriter::computeClassInitializerInfo), and
    //        -- guarantee the instance is initialized with trivial instance
    //           initializer (see CodeRewriter::computeInstanceInitializerInfo)
    //
    //    Risk: instance stored in field X.F was mutated
    //    Solution: we require that class X does not have any instance fields, and
    //      if any of its superclasses has instance fields, accessing them will make
    //      this instance not eligible for inlining. I.e. even though the instance is
    //      publicized and its state has been mutated, it will not effect the logic
    //      of class inlining
    //

    if (eligibleClassDefinition.instanceFields().length > 0 ||
        !eligibleClassDefinition.accessFlags.isFinal()) {
      return false;
    }

    // Singleton instance must be initialized in class constructor.
    DexEncodedMethod classInitializer = eligibleClassDefinition.getClassInitializer();
    if (classInitializer == null || isProcessedConcurrently.test(classInitializer)) {
      return false;
    }

    TrivialInitializer info =
        classInitializer.getOptimizationInfo().getTrivialInitializerInfo();
    assert info == null || info instanceof TrivialClassInitializer;
    DexField instanceField = root.asStaticGet().getField();
    // Singleton instance field must NOT be pinned.
    return info != null &&
        ((TrivialClassInitializer) info).field == instanceField &&
        !appInfo.isPinned(eligibleClassDefinition.lookupStaticField(instanceField).field);
  }

  // Checks if the inlining candidate instance users are eligible,
  // see comment on processMethodCode(...).
  boolean areInstanceUsersEligible(Supplier<InliningOracle> defaultOracle) {
    // No Phi users.
    if (eligibleInstance.numberOfPhiUsers() > 0) {
      return false; // Not eligible.
    }

    for (Instruction user : eligibleInstance.uniqueUsers()) {
      // Field read/write.
      if (user.isInstanceGet() ||
          (user.isInstancePut() && user.asInstancePut().value() != eligibleInstance)) {
        DexField field = user.asFieldInstruction().getField();
        if (field.clazz == eligibleClass &&
            eligibleClassDefinition.lookupInstanceField(field) != null) {
          // Since class inliner currently only supports classes directly extending
          // java.lang.Object, we don't need to worry about fields defined in superclasses.
          continue;
        }
        return false; // Not eligible.
      }

      // Eligible constructor call (for new instance roots only).
      if (user.isInvokeDirect() && root.isNewInstance()) {
        InliningInfo inliningInfo = isEligibleConstructorCall(user.asInvokeDirect());
        if (inliningInfo != null) {
          methodCallsOnInstance.put(user.asInvokeDirect(), inliningInfo);
          continue;
        }
      }

      // Eligible virtual method call on the instance as a receiver.
      if (user.isInvokeVirtual() || user.isInvokeInterface()) {
        InliningInfo inliningInfo = isEligibleDirectMethodCall(user.asInvokeMethodWithReceiver());
        if (inliningInfo != null) {
          methodCallsOnInstance.put(user.asInvokeMethodWithReceiver(), inliningInfo);
          continue;
        }
      }

      // Eligible usage as an invocation argument.
      if (user.isInvokeMethod()) {
        if (isExtraMethodCallEligible(defaultOracle, user.asInvokeMethod())) {
          continue;
        }
      }

      return false;  // Not eligible.
    }
    return true;
  }

  // Process inlining, includes the following steps:
  //
  //  * replace unused instance usages as arguments which are never used
  //  * inline extra methods if any, collect new direct method calls
  //  * inline direct methods if any
  //  * remove superclass initializer call and field reads
  //  * remove field writes
  //  * remove root instruction
  //
  void processInlining(IRCode code, InlinerAction inliner) {
    replaceUsagesAsUnusedArgument(code);
    forceInlineExtraMethodInvocations(inliner);
    forceInlineDirectMethodInvocations(inliner);
    removeSuperClassInitializerAndFieldReads(code);
    removeFieldWrites();
    removeInstruction(root);
  }

  private void replaceUsagesAsUnusedArgument(IRCode code) {
    for (Pair<InvokeMethod, Integer> unusedArgument : unusedArguments) {
      InvokeMethod invoke = unusedArgument.getFirst();
      BasicBlock block = invoke.getBlock();

      ConstNumber nullValue = code.createConstNull();
      nullValue.setPosition(invoke.getPosition());
      LinkedList<Instruction> instructions = block.getInstructions();
      instructions.add(instructions.indexOf(invoke), nullValue);
      nullValue.setBlock(block);

      int argIndex = unusedArgument.getSecond() + (invoke.isInvokeMethodWithReceiver() ? 1 : 0);
      invoke.replaceValue(argIndex, nullValue.outValue());
    }
    unusedArguments.clear();
  }

  private void forceInlineExtraMethodInvocations(InlinerAction inliner) {
    if (extraMethodCalls.isEmpty()) {
      return;
    }

    // Inline extra methods.
    inliner.inline(extraMethodCalls);

    // Reset the collections.
    methodCallsOnInstance.clear();
    extraMethodCalls.clear();
    unusedArguments.clear();
    estimatedCombinedSizeForInlining = 0;

    // Repeat user analysis
    if (!areInstanceUsersEligible(() -> {
      throw new Unreachable("Inlining oracle is expected to be needed");
    })) {
      throw new Unreachable("Analysis must succeed after inlining of extra methods");
    }
    assert extraMethodCalls.isEmpty();
    assert unusedArguments.isEmpty();
  }

  private void forceInlineDirectMethodInvocations(InlinerAction inliner) {
    if (!methodCallsOnInstance.isEmpty()) {
      inliner.inline(methodCallsOnInstance);
    }
  }

  // Remove call to superclass initializer, replace field reads with appropriate
  // values, insert phis when needed.
  private void removeSuperClassInitializerAndFieldReads(IRCode code) {
    Map<DexField, FieldValueHelper> fieldHelpers = new IdentityHashMap<>();
    for (Instruction user : eligibleInstance.uniqueUsers()) {
      // Remove the call to superclass constructor.
      if (root.isNewInstance() &&
          user.isInvokeDirect() &&
          user.asInvokeDirect().getInvokedMethod() == factory.objectMethods.constructor) {
        removeInstruction(user);
        continue;
      }

      if (user.isInstanceGet()) {
        // Replace a field read with appropriate value.
        replaceFieldRead(code, user.asInstanceGet(), fieldHelpers);
        continue;
      }

      if (user.isInstancePut()) {
        // Skip in this iteration since these instructions are needed to
        // properly calculate what value should field reads be replaced with.
        continue;
      }

      throw new Unreachable("Unexpected usage left after method inlining: " + user);
    }
  }

  private void replaceFieldRead(IRCode code,
      InstanceGet fieldRead, Map<DexField, FieldValueHelper> fieldHelpers) {
    Value value = fieldRead.outValue();
    if (value != null) {
      FieldValueHelper helper = fieldHelpers.computeIfAbsent(
          fieldRead.getField(), field -> new FieldValueHelper(field, code, root));
      Value newValue = helper.getValueForFieldRead(fieldRead.getBlock(), fieldRead);
      value.replaceUsers(newValue);
      for (FieldValueHelper fieldValueHelper : fieldHelpers.values()) {
        fieldValueHelper.replaceValue(value, newValue);
      }
      assert value.numberOfAllUsers() == 0;
    }
    removeInstruction(fieldRead);
  }

  private void removeFieldWrites() {
    for (Instruction user : eligibleInstance.uniqueUsers()) {
      if (!user.isInstancePut()) {
        throw new Unreachable("Unexpected usage left after field reads removed: " + user);
      }
      if (user.asInstancePut().getField().clazz != eligibleClass) {
        throw new Unreachable("Unexpected field write left after field reads removed: " + user);
      }
      removeInstruction(user);
    }
  }

  private InliningInfo isEligibleConstructorCall(InvokeDirect initInvoke) {
    // Must be a constructor of the exact same class.
    DexMethod init = initInvoke.getInvokedMethod();
    if (!factory.isConstructor(init)) {
      return null;
    }
    // Must be a constructor called on the receiver.
    if (initInvoke.inValues().lastIndexOf(eligibleInstance) != 0) {
      return null;
    }

    assert init.holder == eligibleClass
        : "Inlined constructor? [invoke: " + initInvoke +
        ", expected class: " + eligibleClass + "]";

    DexEncodedMethod definition = appInfo.definitionFor(init);
    if (definition == null || isProcessedConcurrently.test(definition)) {
      return null;
    }

    if (!definition.isInliningCandidate(method, Reason.SIMPLE, appInfo)) {
      // We won't be able to inline it here.

      // Note that there may be some false negatives here since the method may
      // reference private fields of its class which are supposed to be replaced
      // with arguments after inlining. We should try and improve it later.

      // Using -allowaccessmodification mitigates this.
      return null;
    }

    return definition.getOptimizationInfo().getClassInlinerEligibility() != null
        ? new InliningInfo(definition, eligibleClass) : null;
  }

  private InliningInfo isEligibleDirectMethodCall(InvokeMethodWithReceiver invoke) {
    if (invoke.inValues().lastIndexOf(eligibleInstance) > 0) {
      return null; // Instance passed as an argument.
    }
    return isEligibleMethodCall(invoke.getInvokedMethod(),
        eligibility -> !eligibility.returnsReceiver ||
            invoke.outValue() == null || invoke.outValue().numberOfAllUsers() == 0);
  }

  private InliningInfo isEligibleIndirectMethodCall(DexMethod callee) {
    return isEligibleMethodCall(callee, eligibility -> !eligibility.returnsReceiver);
  }

  private InliningInfo isEligibleMethodCall(
      DexMethod callee, Predicate<ClassInlinerEligibility> eligibilityAcceptanceCheck) {

    DexEncodedMethod singleTarget = findSingleTarget(callee);
    if (singleTarget == null || isProcessedConcurrently.test(singleTarget)) {
      return null;
    }
    if (method == singleTarget) {
      return null; // Don't inline itself.
    }

    ClassInlinerEligibility eligibility =
        singleTarget.getOptimizationInfo().getClassInlinerEligibility();
    if (eligibility == null) {
      return null;
    }

    // If the method returns receiver and the return value is actually
    // used in the code the method is not eligible.
    if (!eligibilityAcceptanceCheck.test(eligibility)) {
      return null;
    }

    if (!singleTarget.isInliningCandidate(method, Reason.SIMPLE, appInfo)) {
      // We won't be able to inline it here.

      // Note that there may be some false negatives here since the method may
      // reference private fields of its class which are supposed to be replaced
      // with arguments after inlining. We should try and improve it later.

      // Using -allowaccessmodification mitigates this.
      return null;
    }

    markSizeForInlining(singleTarget);
    return new InliningInfo(singleTarget, eligibleClass);
  }

  // Analyzes if a method invoke the eligible instance is passed to is eligible. In short,
  // it can be eligible if:
  //
  //   -- eligible instance is passed as argument #N which is not used in the method,
  //      such cases are collected in 'unusedArguments' parameter and later replaced
  //      with 'null' value
  //
  //   -- eligible instance is passed as argument #N which is only used in the method to
  //      call a method on this object (we call it indirect method call), and method is
  //      eligible according to the same rules defined for direct method call eligibility
  //      (except we require the method receiver to not be used in return instruction)
  //
  //   -- method itself can be inlined
  //
  private synchronized boolean isExtraMethodCallEligible(
      Supplier<InliningOracle> defaultOracle, InvokeMethod invokeMethod) {

    List<Value> arguments = Lists.newArrayList(invokeMethod.inValues());

    // Remove receiver from arguments.
    if (invokeMethod.isInvokeMethodWithReceiver()) {
      if (arguments.get(0) == eligibleInstance) {
        // If we got here with invocation on receiver the user is ineligible.
        return false;
      }
      arguments.remove(0);
    }

    // Need single target.
    DexEncodedMethod singleTarget = invokeMethod.computeSingleTarget(appInfo);
    if (singleTarget == null || isProcessedConcurrently.test(singleTarget)) {
      return false;  // Not eligible.
    }

    OptimizationInfo optimizationInfo = singleTarget.getOptimizationInfo();

    // Go through all arguments, see if all usages of eligibleInstance are good.
    for (int argIndex = 0; argIndex < arguments.size(); argIndex++) {
      Value argument = arguments.get(argIndex);
      if (argument != eligibleInstance) {
        continue; // Nothing to worry about.
      }

      // Have parameter usage info?
      ParameterUsage parameterUsage = optimizationInfo.getParameterUsages(argIndex);
      if (parameterUsage == null) {
        return false;  // Don't know anything.
      }

      if (parameterUsage instanceof NotUsed) {
        // Reference can be removed since it's not used.
        unusedArguments.add(new Pair<>(invokeMethod, argIndex));
        continue;
      }

      if (parameterUsage instanceof SingleCallOfArgumentMethod) {
        // Method exactly one time calls a method on passed eligibleInstance.
        SingleCallOfArgumentMethod info = (SingleCallOfArgumentMethod) parameterUsage;
        if (info.type != Type.VIRTUAL && info.type != Type.INTERFACE) {
          return false; // Don't support direct and super calls yet.
        }

        // Is the method called indirectly still eligible?
        InliningInfo potentialInliningInfo = isEligibleIndirectMethodCall(info.method);
        if (potentialInliningInfo != null) {
          // Check if the method is inline-able by standard inliner.
          InlineAction inlineAction =
              invokeMethod.computeInlining(defaultOracle.get(), method.method.holder);
          if (inlineAction != null) {
            extraMethodCalls.put(invokeMethod, new InliningInfo(singleTarget, null));
            continue;
          }
        }

        return false;
      }

      return false; // All other argument usages are not eligible.
    }

    // Looks good.
    markSizeForInlining(singleTarget);
    return true;
  }

  private boolean exemptFromInstructionLimit(DexEncodedMethod inlinee) {
    DexType inlineeHolder = inlinee.method.holder;
    if (appInfo.isPinned(inlineeHolder)) {
      return false;
    }
    DexClass inlineeClass = appInfo.definitionFor(inlineeHolder);
    assert inlineeClass != null;

    KotlinInfo kotlinInfo = inlineeClass.getKotlinInfo();
    return kotlinInfo != null &&
        kotlinInfo.isSyntheticClass() &&
        kotlinInfo.asSyntheticClass().isLambda();
  }

  private void markSizeForInlining(DexEncodedMethod inlinee) {
    if (!exemptFromInstructionLimit(inlinee)) {
      estimatedCombinedSizeForInlining += inlinee.getCode().estimatedSizeForInlining();
    }
  }

  private boolean isNewInstance() {
    return root.isNewInstance();
  }

  private DexEncodedMethod findSingleTarget(DexMethod callee) {
    // We don't use computeSingleTarget(...) on invoke since it sometimes fails to
    // find the single target, while this code may be more successful since we exactly
    // know what is the actual type of the receiver.

    // Note that we also intentionally limit ourselves to methods directly defined in
    // the instance's class. This may be improved later.
    return eligibleClassDefinition.lookupVirtualMethod(callee);
  }

  private void removeInstruction(Instruction instruction) {
    instruction.inValues().forEach(v -> v.removeUser(instruction));
    instruction.getBlock().removeInstruction(instruction);
  }
}
