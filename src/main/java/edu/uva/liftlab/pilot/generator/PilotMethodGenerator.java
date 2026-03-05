package edu.uva.liftlab.pilot.generator;

import edu.uva.liftlab.pilot.isolation.stateredirection.ClassFilterHelper;
import edu.uva.liftlab.pilot.transformer.PilotTransformer;
import edu.uva.liftlab.pilot.util.LocalGeneratorUtil;
import edu.uva.liftlab.pilot.util.SootUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;
import soot.tagkit.Tag;

import java.util.*;

import static edu.uva.liftlab.pilot.transformer.PilotTransformer.SET_BY_DRY_RUN;
import static edu.uva.liftlab.pilot.util.Constants.*;
import static edu.uva.liftlab.pilot.util.SootUtils.*;

public class PilotMethodGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(PilotMethodGenerator.class);
    public ClassFilterHelper filter;

    public PilotMethodGenerator(ClassFilterHelper filter) {
        this.filter = filter;
    }

    public void addDivergeMethod(SootClass sc){
        List<SootMethod> methods = new ArrayList<>(sc.getMethods());
        for (SootMethod method : methods) {
            if(method.getName().contains(LAMBDA_BOOT_STRAP)){
                continue;
            }
            PilotTransformer.transformationCount++;
            this.addInstrumentedFunction(method, sc);
            this.addOriginalFunction(method, sc);
            this.addDryRunDivergeCode2OriginalFunc(method, sc);
        }
    }


    public void processClasses() {
        for (SootClass sc : Scene.v().getApplicationClasses()) {
            boolean shouldSkip = filter.shouldSkip(sc);
            boolean isPilotFuncBlackListClass = filter.isBlackListPilotFuncClass(sc) && !filter.isWhiteListPilotFuncClass(sc);
            boolean stateBWEnabled = filter.isStateBWEnabled;
            boolean isStateIsolationBlackListClass = filter.isStateBlackListClass(sc) && !filter.isStateWhiteListClass(sc);

            if(shouldSkip){
                continue;
            }


            if(!isPilotFuncBlackListClass){
                addDivergeMethod(sc);
            }

            if(!stateBWEnabled){
                if(!isPilotFuncBlackListClass){
                    addDryRunFields(sc);
                }
            }else if(stateBWEnabled){
                if(!isPilotFuncBlackListClass && !isStateIsolationBlackListClass){
                    addDryRunFields(sc);
                }
            }
        }

        for (SootClass sc : Scene.v().getApplicationClasses()) {
            if(filter.shouldSkip(sc)){
                continue;
            }
            replaceFunctionCallIteratively(sc, filter, INSTRUMENTATION_SUFFIX);
        }

        // Note: the following is an optimization to minimize if(PilotUtil.isDryRun()) check overhead, but it seems to cause some issues in certain cases,
        // needs a better engineering solution later

//        for (SootClass sc: Scene.v().getApplicationClasses()){
//            if(filter.shouldSkip(sc)){
//                continue;
//            }
//            replaceFunctionCallIteratively(sc, filter, ORIGINAL_SUFFIX);
//        }
//
//        for (SootClass sc: Scene.v().getApplicationClasses()){
//            if(filter.shouldSkip(sc)){
//                continue;
//            }
//            instrumentOriginalFuncCall(sc, filter, ORIGINAL_SUFFIX);
//        }
    }

    public boolean hasSuffix(SootMethod method){
        return method.getName().endsWith(INSTRUMENTATION_SUFFIX) || method.getName().endsWith(ORIGINAL_SUFFIX);
    }

    public void instrumentOriginalFuncCall(SootClass sc, ClassFilterHelper filter, String suffix) {
        for (SootMethod method : sc.getMethods()) {
            // Only process instrumented methods
            if (!method.hasActiveBody() || hasSuffix(method)) {
                continue;
            }

            Body body = method.getActiveBody();

            // Iterate through all units in the method body
            for (Unit unit : new ArrayList<>(body.getUnits())) {
                // Look for invoke expressions
                if (!(unit instanceof InvokeStmt) && !(unit instanceof AssignStmt)) {
                    continue;
                }

                InvokeExpr invokeExpr = null;
                if (unit instanceof InvokeStmt) {
                    invokeExpr = ((InvokeStmt) unit).getInvokeExpr();
                } else if (unit instanceof AssignStmt) {
                    Value rightOp = ((AssignStmt) unit).getRightOp();
                    if (rightOp instanceof InvokeExpr) {
                        invokeExpr = (InvokeExpr) rightOp;
                    }
                }

                if (invokeExpr == null || invokeExpr.getMethod().getName().equals("<init>") || hasSuffix(invokeExpr.getMethod())) {
                    continue;
                }

                // Get the method being called
                SootMethod targetMethod = invokeExpr.getMethod();
                SootClass targetClass = targetMethod.getDeclaringClass();

                // Skip if the target class should be filtered out

                // Try to find the instrumented version of the method
                String instrumentedMethodName = targetMethod.getName() + suffix;
                SootMethod instrumentedMethod;
                if(!targetClass.declaresMethod(instrumentedMethodName, targetMethod.getParameterTypes(), targetMethod.getReturnType())) {
                    continue;
                }
                try {
                    instrumentedMethod = targetClass.getMethod(
                            instrumentedMethodName,
                            targetMethod.getParameterTypes(),
                            targetMethod.getReturnType()
                    );
                } catch (RuntimeException e) {
                    // Instrumented version doesn't exist, skip
                    continue;
                }

                // Create new invoke expression for the instrumented method
                InvokeExpr newInvokeExpr;
                LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

                if (invokeExpr instanceof StaticInvokeExpr) {
                    newInvokeExpr = Jimple.v().newStaticInvokeExpr(
                            instrumentedMethod.makeRef(),
                            invokeExpr.getArgs()
                    );
                } else {
                    // Generate local variable for the base
                    Local baseLocal;
                    Value base;

                    if (invokeExpr instanceof VirtualInvokeExpr) {
                        base = ((VirtualInvokeExpr) invokeExpr).getBase();
                    } else if (invokeExpr instanceof SpecialInvokeExpr) {
                        base = ((SpecialInvokeExpr) invokeExpr).getBase();
                    } else if (invokeExpr instanceof InterfaceInvokeExpr) {
                        base = ((InterfaceInvokeExpr) invokeExpr).getBase();
                    } else {
                        continue;
                    }

                    // If base is not already a Local, create a new local and assign the base to it
                    if (!(base instanceof Local)) {
                        baseLocal = lg.generateLocal(base.getType());
                        Unit assignBase = Jimple.v().newAssignStmt(baseLocal, base);
                        body.getUnits().insertBefore(assignBase, unit);
                    } else {
                        baseLocal = (Local) base;
                    }

                    // Create appropriate invoke expression based on type
                    if (invokeExpr instanceof VirtualInvokeExpr) {
                        newInvokeExpr = Jimple.v().newVirtualInvokeExpr(
                                baseLocal,
                                instrumentedMethod.makeRef(),
                                invokeExpr.getArgs()
                        );
                    } else if (invokeExpr instanceof SpecialInvokeExpr) {
                        newInvokeExpr = Jimple.v().newSpecialInvokeExpr(
                                baseLocal,
                                instrumentedMethod.makeRef(),
                                invokeExpr.getArgs()
                        );
                    } else { // InterfaceInvokeExpr
                        newInvokeExpr = Jimple.v().newInterfaceInvokeExpr(
                                baseLocal,
                                instrumentedMethod.makeRef(),
                                invokeExpr.getArgs()
                        );
                    }
                }

                // Replace the old invoke expression with the new one
                if (unit instanceof InvokeStmt) {
                    body.getUnits().insertBefore(
                            Jimple.v().newInvokeStmt(newInvokeExpr),
                            unit
                    );
                    body.getUnits().remove(unit);
                } else if (unit instanceof AssignStmt) {
                    AssignStmt assignStmt = (AssignStmt) unit;
                    body.getUnits().insertBefore(
                            Jimple.v().newAssignStmt(assignStmt.getLeftOp(), newInvokeExpr),
                            unit
                    );
                    body.getUnits().remove(unit);
                }
            }

            body.validate();
        }
    }

    public void replaceFunctionCallIteratively(SootClass sc, ClassFilterHelper filter, String suffix) {
        for (SootMethod method : sc.getMethods()) {
            // Only process instrumented methods
            if (!method.getName().endsWith(suffix) || !method.hasActiveBody()) {
                continue;
            }

            Body body = method.getActiveBody();

            // Iterate through all units in the method body
            for (Unit unit : new ArrayList<>(body.getUnits())) {
                // Look for invoke expressions
                if (!(unit instanceof InvokeStmt) && !(unit instanceof AssignStmt)) {
                    continue;
                }

                InvokeExpr invokeExpr = null;
                if (unit instanceof InvokeStmt) {
                    invokeExpr = ((InvokeStmt) unit).getInvokeExpr();
                } else if (unit instanceof AssignStmt) {
                    Value rightOp = ((AssignStmt) unit).getRightOp();
                    if (rightOp instanceof InvokeExpr) {
                        invokeExpr = (InvokeExpr) rightOp;
                    }
                }

                if (invokeExpr == null || invokeExpr.getMethod().getName().equals("<init>")) {
                    continue;
                }

                // Get the method being called
                SootMethod targetMethod = invokeExpr.getMethod();
                SootClass targetClass = targetMethod.getDeclaringClass();

                // Skip if the target class should be filtered out

                // Try to find the instrumented version of the method
                String instrumentedMethodName = targetMethod.getName() + suffix;
                SootMethod instrumentedMethod;
                //see if instrumentedMethod exists
                if(!targetClass.declaresMethod(instrumentedMethodName, targetMethod.getParameterTypes(), targetMethod.getReturnType())) {
                    continue;
                }
                try {
                    instrumentedMethod = targetClass.getMethod(
                            instrumentedMethodName,
                            targetMethod.getParameterTypes(),
                            targetMethod.getReturnType()
                    );
                } catch (RuntimeException e) {
                    // Instrumented version doesn't exist, skip
                    continue;
                }

                // Create new invoke expression for the instrumented method
                InvokeExpr newInvokeExpr;
                LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

                if (invokeExpr instanceof StaticInvokeExpr) {
                    newInvokeExpr = Jimple.v().newStaticInvokeExpr(
                            instrumentedMethod.makeRef(),
                            invokeExpr.getArgs()
                    );
                } else {
                    // Generate local variable for the base
                    Local baseLocal;
                    Value base;

                    if (invokeExpr instanceof VirtualInvokeExpr) {
                        base = ((VirtualInvokeExpr) invokeExpr).getBase();
                    } else if (invokeExpr instanceof SpecialInvokeExpr) {
                        base = ((SpecialInvokeExpr) invokeExpr).getBase();
                    } else if (invokeExpr instanceof InterfaceInvokeExpr) {
                        base = ((InterfaceInvokeExpr) invokeExpr).getBase();
                    } else {
                        continue;
                    }

                    // If base is not already a Local, create a new local and assign the base to it
                    if (!(base instanceof Local)) {
                        baseLocal = lg.generateLocal(base.getType());
                        Unit assignBase = Jimple.v().newAssignStmt(baseLocal, base);
                        body.getUnits().insertBefore(assignBase, unit);
                    } else {
                        baseLocal = (Local) base;
                    }

                    // Create appropriate invoke expression based on type
                    if (invokeExpr instanceof VirtualInvokeExpr) {
                        newInvokeExpr = Jimple.v().newVirtualInvokeExpr(
                                baseLocal,
                                instrumentedMethod.makeRef(),
                                invokeExpr.getArgs()
                        );
                    } else if (invokeExpr instanceof SpecialInvokeExpr) {
                        newInvokeExpr = Jimple.v().newSpecialInvokeExpr(
                                baseLocal,
                                instrumentedMethod.makeRef(),
                                invokeExpr.getArgs()
                        );
                    } else { // InterfaceInvokeExpr
                        newInvokeExpr = Jimple.v().newInterfaceInvokeExpr(
                                baseLocal,
                                instrumentedMethod.makeRef(),
                                invokeExpr.getArgs()
                        );
                    }
                }

                // Replace the old invoke expression with the new one
                if (unit instanceof InvokeStmt) {
                    body.getUnits().insertBefore(
                            Jimple.v().newInvokeStmt(newInvokeExpr),
                            unit
                    );
                    body.getUnits().remove(unit);
                } else if (unit instanceof AssignStmt) {
                    AssignStmt assignStmt = (AssignStmt) unit;
                    body.getUnits().insertBefore(
                            Jimple.v().newAssignStmt(assignStmt.getLeftOp(), newInvokeExpr),
                            unit
                    );
                    body.getUnits().remove(unit);
                }
            }

            body.validate();
        }
    }


    private void addDryRunFields(SootClass sootClass){
        if (sootClass.isEnum() || sootClass.isInterface()) {
            return;
        }
        LOG.info("Processing class field instrumentation: " + sootClass.getName());

        ArrayList<SootField> originalFields = new ArrayList<>(sootClass.getFields());
        for(SootField originalField : originalFields) {
//            if (originalField.getName().endsWith(DRY_RUN_SUFFIX)) {
//                continue;
//            }
            if(originalField.getName().contains("assertionsDisabled")){
                continue;
            }

            //Workaround for Solr Cache
            if(originalField.getName().contains("shadowDirPath")){
                continue;
            }

            if(SootUtils.isLogType(originalField) || SootUtils.isImmutableCollection(originalField)){
                continue;
            }

            String newFieldName = originalField.getName() + DRY_RUN_SUFFIX;
            String setByDryRunFieldName = newFieldName + SET_BY_DRY_RUN;

            if(sootClass.declaresFieldByName(newFieldName)) {
                continue;
            }
            int newModifiers = originalField.getModifiers();
            String originalSignature = originalField.getSignature();
            newModifiers &= ~Modifier.FINAL;
            newModifiers |= Modifier.PUBLIC;
            Type fieldType = originalField.getType();
            SootField newField = new SootField(
                    newFieldName,
                    fieldType,
                    newModifiers
                    // default to null for other types
            );
            for (Tag tag : originalField.getTags()) {
                newField.addTag(tag);
            }
            SootField setByDryRunField = new SootField(
                    setByDryRunFieldName,
                    IntType.v(),
                    newModifiers
            );

            try {
                sootClass.addField(newField);
                sootClass.addField(setByDryRunField);
                //LOG.info("Added dryrun field {} to class {}", newFieldName, sootClass.getName());
            } catch (Exception e) {
                LOG.error("Failed to add field {} to class {}: {}",
                        newFieldName, sootClass.getName(), e.getMessage());
            }
        }
    }


    public void addInstrumentedFunction(SootMethod originalMethod, SootClass sootClass) {
        if(!originalMethodShouldBeInstrumented(originalMethod, sootClass)) {
            return;
        }

        //print all of the soot methods of sootclass
//        for(SootMethod sm:sootClass.getMethods()){
//            LOG.info("SootMethod: "+sm.getName()+ " in class "+sootClass.getName());
//        }

        String instrumentationMethodName = getInstrumnentationMethodName(originalMethod);
        SootMethod instrumentationMethod = new SootMethod(
                instrumentationMethodName,
                originalMethod.getParameterTypes(),
                originalMethod.getReturnType(),
                originalMethod.getModifiers()
        );
        //judge if the method already exists
        if (sootClass.declaresMethod(instrumentationMethodName, originalMethod.getParameterTypes(), originalMethod.getReturnType())) {
            LOG.warn("Method {} already exists in class {}, skipping instrumentation.", instrumentationMethodName, sootClass.getName());
            return;
        }
        sootClass.addMethod(instrumentationMethod);

        Body originalBody = originalMethod.retrieveActiveBody();
        Body instrumentationBody = Jimple.v().newBody(instrumentationMethod);

        if (originalMethod.isConstructor()) {
            handleConstructorInstrumentation(originalBody, instrumentationBody);
        } else {
            instrumentationBody.importBodyContentsFrom(originalBody);
        }

        instrumentationMethod.setActiveBody(instrumentationBody);
    }


    public void addOriginalFunction(SootMethod originalMethod, SootClass sootClass) {
        if(!originalMethodShouldBeInstrumented(originalMethod, sootClass)) {
            return;
        }

        //print all of the soot methods of sootclass
//        for(SootMethod sm:sootClass.getMethods()){
//            LOG.info("SootMethod: "+sm.getName()+ " in class "+sootClass.getName());
//        }

        String instrumentationMethodName = getOriginalMethodName(originalMethod);
        SootMethod instrumentationMethod = new SootMethod(
                instrumentationMethodName,
                originalMethod.getParameterTypes(),
                originalMethod.getReturnType(),
                originalMethod.getModifiers()
        );
        if (sootClass.declaresMethod(instrumentationMethodName, originalMethod.getParameterTypes(), originalMethod.getReturnType())) {
            LOG.warn("Method {} already exists in class {}, skipping instrumentation.", instrumentationMethodName, sootClass.getName());
            return;
        }
        sootClass.addMethod(instrumentationMethod);

        Body originalBody = originalMethod.retrieveActiveBody();
        Body instrumentationBody = Jimple.v().newBody(instrumentationMethod);

        if (originalMethod.isConstructor()) {
            handleConstructorInstrumentation(originalBody, instrumentationBody);
        } else {
            instrumentationBody.importBodyContentsFrom(originalBody);
        }

        instrumentationMethod.setActiveBody(instrumentationBody);
    }

    private void  handleConstructorInstrumentation(Body originalBody, Body instrumentationBody) {
        instrumentationBody.importBodyContentsFrom(originalBody);

        SootClass currentClass = originalBody.getMethod().getDeclaringClass();
        SootClass superClass = currentClass.getSuperclass();

        PatchingChain<Unit> units = instrumentationBody.getUnits();
        Unit lastConstructorCall = null;
        Unit firstNonIdentityStmt = null;

        for (Unit u : units.toArray(new Unit[0])) {
            if (firstNonIdentityStmt == null && !(u instanceof IdentityStmt)) {
                firstNonIdentityStmt = u;
            }
            if (u instanceof InvokeStmt) {
                InvokeStmt invoke = (InvokeStmt) u;
                if (invoke.getInvokeExpr() instanceof SpecialInvokeExpr) {
                    SpecialInvokeExpr special = (SpecialInvokeExpr) invoke.getInvokeExpr();
                    if (special.getMethod().getName().equals("<init>") &&
                            (special.getMethod().getDeclaringClass().equals(currentClass) ||
                                    special.getMethod().getDeclaringClass().equals(superClass))) {
                        lastConstructorCall = u;
                    }
                }
            }
        }

        if (firstNonIdentityStmt != null && lastConstructorCall != null) {
            boolean startRemoving = false;
            for (Unit u : units.toArray(new Unit[0])) {
                if (u == firstNonIdentityStmt) {
                    startRemoving = true;
                }
                if (startRemoving) {
                    units.remove(u);
                    if (u == lastConstructorCall) {
                        break;
                    }
                }
            }
        }
    }

    private Value getDefaultValueForType(Type t) {
        if (t instanceof BooleanType) return IntConstant.v(0); // false
        if (t instanceof CharType) return IntConstant.v(0);
        if (t instanceof ByteType) return IntConstant.v(0);
        if (t instanceof ShortType) return IntConstant.v(0);
        if (t instanceof IntType) return IntConstant.v(0);
        if (t instanceof LongType) return LongConstant.v(0L);
        if (t instanceof FloatType) return FloatConstant.v(0.0f);
        if (t instanceof DoubleType) return DoubleConstant.v(0.0);
        return NullConstant.v();
    }


    public void addDryRunDivergeCode2OriginalFunc(SootMethod originalMethod, SootClass sc){
//        if(originalMethod.isConstructor()){
//            addDryRunDivergeCode2Constructor(originalMethod, sc, filter);
//        }else{
//            addDryRunDivergeCode2NonConstructor(originalMethod, sc, filter);
//        }
        addDryRunDivergeCode2NonConstructor(originalMethod, sc, filter);
     }

    private void addDryRunDivergeCode2Constructor(SootMethod originalMethod, SootClass sc, ClassFilterHelper filter) {
        if(!originalMethodShouldBeInstrumented(originalMethod, sc)) {
            return;
        }

        String instrumentationMethodName = getInstrumnentationMethodName(originalMethod);
        SootMethod instrumentationMethod = sc.getMethod(
                instrumentationMethodName,
                originalMethod.getParameterTypes(),
                originalMethod.getReturnType()
        );
        Body originalBody = originalMethod.getActiveBody();
        Body newBody = (Body) originalBody.clone();
        PatchingChain<Unit> units = newBody.getUnits();
        LocalGeneratorUtil lg = new LocalGeneratorUtil(newBody);

        Unit lastConstructorCall = null;
        Unit firstNonConstructorStmt = null;
        SootClass currentClass = sc;
        SootClass superClass = sc.getSuperclass();

        for(Unit u : units) {
            if(u instanceof InvokeStmt) {
                InvokeStmt invoke = (InvokeStmt)u;
                if(invoke.getInvokeExpr() instanceof SpecialInvokeExpr) {
                    SpecialInvokeExpr special = (SpecialInvokeExpr)invoke.getInvokeExpr();

                    if (special.getMethod().getName().equals("<init>") &&
                            (special.getMethod().getDeclaringClass().equals(currentClass) ||
                                    special.getMethod().getDeclaringClass().equals(superClass) ||
                                    special.getMethod().getDeclaringClass().getName().equals("java.lang.Object"))) {
                        lastConstructorCall = u;
                        LOG.info("Found constructor call: " + special.getMethod().getDeclaringClass().getName());
                        LOG.info("Last constructor call: " + lastConstructorCall);
                        continue;
                    }
                }
            }

            if(lastConstructorCall != null) {
                firstNonConstructorStmt = u;
                break;
            }
        }

        if(lastConstructorCall == null) {
            throw new RuntimeException("No constructor call found in constructor method");
        }

        Local isDryRunLocal = lg.generateLocal(BooleanType.v());
        StaticInvokeExpr isDryRunExpr = Jimple.v().newStaticInvokeExpr(
                Scene.v().makeMethodRef(
                        Scene.v().loadClassAndSupport(PILOT_UTIL_CLASS_NAME),
                        "isDryRun",
                        Collections.emptyList(),
                        BooleanType.v(),
                        true
                )
        );
        Unit assignDryRun = Jimple.v().newAssignStmt(isDryRunLocal, isDryRunExpr);
        units.insertAfter(assignDryRun, lastConstructorCall);

        GotoStmt gotoOriginal = Jimple.v().newGotoStmt(firstNonConstructorStmt);

        IfStmt ifStmt = Jimple.v().newIfStmt(
                Jimple.v().newEqExpr(isDryRunLocal, IntConstant.v(0)),
                gotoOriginal
        );
        units.insertAfter(ifStmt, assignDryRun);

        InvokeExpr invokeInstrumentation = Jimple.v().newVirtualInvokeExpr(
                newBody.getThisLocal(),
                instrumentationMethod.makeRef(),
                newBody.getParameterLocals()
        );

        String logInput = "Enter dry run method <init> in class " + sc.getName();
        List<Unit> logInputPrintUnits = printLog4j(logInput, lg);
        String logFinish = "Successfully finish dry run method <init> in class " + sc.getName();
        List<Unit> logFinishPrintUnits = printLog4j(logFinish, lg);

        Unit invokeStmt = Jimple.v().newInvokeStmt(invokeInstrumentation);
        Unit returnVoidStmt = Jimple.v().newReturnVoidStmt();

        units.insertAfter(invokeStmt, ifStmt);
        for(Unit u : logInputPrintUnits) {
            units.insertBefore(u, invokeStmt);
        }
        units.insertAfter(returnVoidStmt, invokeStmt);
        for(Unit u : logFinishPrintUnits) {
            units.insertBefore(u, returnVoidStmt);
        }
        units.insertAfter(gotoOriginal, returnVoidStmt);

        originalMethod.setActiveBody(newBody);
        newBody.validate();
    }


    public void addDryRunDivergeCode2NonConstructor(SootMethod originalMethod, SootClass sc, ClassFilterHelper filter) {
        if(!originalMethodShouldBeInstrumented(originalMethod, sc)) {
            return;
        }

        String instrumentationMethodName = getInstrumnentationMethodName(originalMethod);
        SootMethod instrumentationMethod = sc.getMethod(
                instrumentationMethodName,
                originalMethod.getParameterTypes(),
                originalMethod.getReturnType()
        );
        Body originalBody = originalMethod.getActiveBody();
        Body newBody = (Body) originalBody.clone();
        PatchingChain<Unit> units = newBody.getUnits();
        LocalGeneratorUtil lg = new LocalGeneratorUtil(newBody);
        Local isDryRunLocal = lg.generateLocal(BooleanType.v());
        StaticInvokeExpr isDryRunExpr = Jimple.v().newStaticInvokeExpr(
                Scene.v().makeMethodRef(
                        Scene.v().loadClassAndSupport(PILOT_UTIL_CLASS_NAME),
                        "isDryRun",
                        Collections.emptyList(),
                        BooleanType.v(),
                        true
                )
        );
        Unit assignDryRun = Jimple.v().newAssignStmt(isDryRunLocal, isDryRunExpr);
        Unit lastIdentityStmt = null;
        Unit firstNonIdentityStmt = null;
        boolean foundNonIdentity = false;

        for (Unit u : units) {
            if (!foundNonIdentity) {
                if (u instanceof IdentityStmt) {
                    lastIdentityStmt = u;
                } else {
                    firstNonIdentityStmt = u;
                    foundNonIdentity = true;
                }
            }
        }
        if (lastIdentityStmt != null) {
            units.insertAfter(assignDryRun, lastIdentityStmt);
        } else {
            units.insertBefore(assignDryRun, units.getFirst());
        }
        // Go to original code
        GotoStmt gotoOriginal = Jimple.v().newGotoStmt(firstNonIdentityStmt);

        // if isDryRun == false;
        IfStmt ifStmt = Jimple.v().newIfStmt(
                Jimple.v().newEqExpr(isDryRunLocal, IntConstant.v(0)),
                gotoOriginal
        );
        units.insertAfter(ifStmt, assignDryRun);

        // Create instrumentation call
        InvokeExpr invokeInstrumentation;
        if (originalMethod.isStatic()) {
            invokeInstrumentation = Jimple.v().newStaticInvokeExpr(
                    instrumentationMethod.makeRef(),
                    newBody.getParameterLocals()
            );
        } else {
//            invokeInstrumentation = Jimple.v().newVirtualInvokeExpr(
//                    newBody.getThisLocal(),
//                    instrumentationMethod.makeRef(),
//                    newBody.getParameterLocals()
//            );
            invokeInstrumentation = Jimple.v().newSpecialInvokeExpr(
                    newBody.getThisLocal(),
                    sc.getMethod(instrumentationMethodName,
                            originalMethod.getParameterTypes(),
                            originalMethod.getReturnType()).makeRef(),
                    newBody.getParameterLocals()
            );
        }

        String logInput = "Enter dry run method "+ originalMethod.getName()+" in class "+sc.getName();
        List<Unit> logInputPrintUnits = printLog4j(logInput,lg);
        String logFinish = "Successfully finish dry run method "+ originalMethod.getName()+" in class "+sc.getName();
        List<Unit> logFinishPrintUnits = printLog4j(logFinish,lg);


        if (!(originalMethod.getReturnType() instanceof VoidType)) {
            Local resultLocal = lg.generateLocal(originalMethod.getReturnType());
            Unit assignResult = Jimple.v().newAssignStmt(resultLocal, invokeInstrumentation);
            Unit returnStmt = Jimple.v().newReturnStmt(resultLocal);

            units.insertAfter(assignResult, ifStmt);
            for(Unit u: logInputPrintUnits){
                units.insertBefore(u, assignResult);
            }
            units.insertAfter(returnStmt, assignResult);
            for (Unit u : logFinishPrintUnits) {
                units.insertBefore(u, returnStmt);
            }
            units.insertAfter(gotoOriginal, returnStmt);
        } else {
            Unit invokeStmt = Jimple.v().newInvokeStmt(invokeInstrumentation);
            Unit returnVoidStmt = Jimple.v().newReturnVoidStmt();

            units.insertAfter(invokeStmt, ifStmt);
            for(Unit u:logInputPrintUnits){
                units.insertBefore(u, invokeStmt);
            }
            units.insertAfter(returnVoidStmt, invokeStmt);
            for (Unit u : logFinishPrintUnits) {
                units.insertBefore(u, returnVoidStmt);
            }
            units.insertAfter(gotoOriginal, returnVoidStmt);
        }

        originalMethod.setActiveBody(newBody);
        newBody.validate();
    }

}