package edu.uva.liftlab.pilot.transformer;

import edu.uva.liftlab.pilot.staticanalysis.RecoveryPattern;
import edu.uva.liftlab.pilot.staticanalysis.TrapAnalyzer;
import edu.uva.liftlab.pilot.util.LocalGeneratorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;
import soot.tagkit.Tag;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.graph.UnitGraph;
import soot.util.Chain;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Transforms exception handlers to execute recovery logic in a new thread with pilot execution context
 */
public class ErrorHandlerTransformer {
    private static final Logger LOG = LoggerFactory.getLogger(ErrorHandlerTransformer.class);
    private static final AtomicInteger methodCounter = new AtomicInteger(0);

    private final TrapAnalyzer trapAnalyzer;

    public ErrorHandlerTransformer(TrapAnalyzer trapAnalyzer) {
        this.trapAnalyzer = trapAnalyzer;
    }

    /**
     * Transform a catch block to execute recovery logic in a new thread with pilot context
     *
     * @param method The method containing the catch block
     * @param trap The trap representing the catch block
     * @param pattern The recovery pattern identified
     */
    public void transformCatchBlock(SootMethod method, Trap trap, RecoveryPattern pattern) {
        LOG.info("Starting transformation of catch block in method {} for exception type {}",
                method.getName(), trap.getException().getName());

        Body body = method.getActiveBody();

        // Skip if body is invalid
        try {
            body.validate();
        } catch (Exception e) {
            LOG.warn("Body validation failed before transformation, skipping: {}", e.getMessage());
            return;
        }

        UnitGraph cfg = new ExceptionalUnitGraph(body);

        // Get catch block units
        Set<Unit> catchBlockUnits = trapAnalyzer.identifyCatchBlockUnits(trap.getHandlerUnit(), body, cfg);
        List<Unit> orderedCatchUnits = getOrderedCatchUnits(catchBlockUnits, body, trap.getHandlerUnit());

        if (orderedCatchUnits.isEmpty()) {
            LOG.warn("No units found in catch block for method {}", method.getName());
            return;
        }

        LOG.info("Found {} units in catch block to transform", orderedCatchUnits.size());

        try {
            // Extract the catch block into a separate method
            SootMethod extractedMethod = extractCatchBlockToMethod(
                    body.getMethod().getDeclaringClass(),
                    orderedCatchUnits,
                    trap,
                    body
            );

            // Create the pilot execution wrapper (but don't remove original units)
            insertPilotExecutionWrapper(body, trap, extractedMethod, orderedCatchUnits);

            // Validate the transformed body
            body.validate();

            LOG.info("Successfully transformed catch block in method {} to use pilot execution",
                    method.getName());

        } catch (Exception e) {
            LOG.error("Failed to transform catch block in method {}: {}", method.getName(), e.getMessage(), e);
            // Don't throw - let the analysis continue with other methods
        }
    }

    /**
     * Extract catch block units into a separate method
     */
    private SootMethod extractCatchBlockToMethod(SootClass containingClass, List<Unit> catchUnits,
                                                 Trap trap, Body originalBody) {
        // First, collect all locals used in catch block
        Set<Local> usedLocals = collectUsedLocals(catchUnits);

        // Find the caught exception local from the first unit (should be identity stmt)
        Local caughtExceptionLocal = null;
        for (Unit unit : catchUnits) {
            if (unit instanceof IdentityStmt) {
                IdentityStmt stmt = (IdentityStmt) unit;
                if (stmt.getRightOp() instanceof CaughtExceptionRef) {
                    caughtExceptionLocal = (Local) stmt.getLeftOp();
                    break;
                }
            }
        }

        // Separate locals that are defined in catch vs those that come from outside
        Set<Local> definedInCatch = new HashSet<>();
        for (Unit unit : catchUnits) {
            for (ValueBox defBox : unit.getDefBoxes()) {
                if (defBox.getValue() instanceof Local) {
                    definedInCatch.add((Local) defBox.getValue());
                }
            }
        }

        // Locals that are used but not defined in catch need to be parameters
        List<Type> paramTypes = new ArrayList<>();
        List<Local> paramLocals = new ArrayList<>();

        // Add caught exception as first parameter if it exists
        if (caughtExceptionLocal != null) {
            paramTypes.add(caughtExceptionLocal.getType());
            paramLocals.add(caughtExceptionLocal);
        }

        for (Local local : usedLocals) {
            if (!definedInCatch.contains(local) && !isThisLocal(local, originalBody)
                    && local != caughtExceptionLocal) {
                paramTypes.add(local.getType());
                paramLocals.add(local);
            }
        }

        // Create method signature
        String methodName = "catchHandler$" + methodCounter.getAndIncrement();
        int modifiers = Modifier.PRIVATE;
        if (originalBody.getMethod().isStatic()) {
            modifiers |= Modifier.STATIC;
        }

        SootMethod extractedMethod = new SootMethod(methodName, paramTypes, VoidType.v(), modifiers);
        containingClass.addMethod(extractedMethod);

        // Create method body
        Body extractedBody = Jimple.v().newBody(extractedMethod);
        extractedMethod.setActiveBody(extractedBody);

        // Add this local if needed (only for non-static methods)
        Local thisLocal = null;
        if (!originalBody.getMethod().isStatic()) {
            thisLocal = Jimple.v().newLocal("this", containingClass.getType());
            extractedBody.getLocals().add(thisLocal);
            extractedBody.getUnits().add(Jimple.v().newIdentityStmt(thisLocal,
                    Jimple.v().newThisRef(containingClass.getType())));
        }

        // Add parameter locals
        Map<Local, Local> localMapping = new HashMap<>();
        for (int i = 0; i < paramLocals.size(); i++) {
            Local paramLocal = Jimple.v().newLocal("param" + i, paramTypes.get(i));
            extractedBody.getLocals().add(paramLocal);
            extractedBody.getUnits().add(Jimple.v().newIdentityStmt(paramLocal,
                    Jimple.v().newParameterRef(paramTypes.get(i), i)));
            localMapping.put(paramLocals.get(i), paramLocal);
        }

        // Map this local if present
        if (thisLocal != null && originalBody.getThisLocal() != null) {
            localMapping.put(originalBody.getThisLocal(), thisLocal);
        }

        // Store the parameter locals for later use
        extractedMethod.addTag(new ParameterLocalsTag(paramLocals));

        copyUnitsWithMapping(catchUnits, extractedBody, localMapping);

        // Ensure method ends with return
        ensureMethodReturns(extractedBody);

        return extractedMethod;
    }

    /**
     * Tag to store parameter locals information
     */
    private static class ParameterLocalsTag implements Tag {
        private final List<Local> paramLocals;

        public ParameterLocalsTag(List<Local> paramLocals) {
            this.paramLocals = new ArrayList<>(paramLocals);
        }

        public List<Local> getParamLocals() {
            return paramLocals;
        }

        @Override
        public String getName() {
            return "ParameterLocalsTag";
        }

        @Override
        public byte[] getValue() {
            return new byte[0];
        }
    }

    /**
     * Insert pilot execution wrapper after the handler unit.
     *
     * Generated code equivalent:
     *   Runnable r = new MethodCallRunnable(caughtException, ...args);
     *   Context ctx = PilotUtil.start(r);
     *   PilotUtil.waitUntilPilotExecutionFinished(ctx);
     *   // original catch block units continue after this
     *   // Note: Large-scale transformation for all the candidate error handlers sometimes makes the system enter an undefined state;
     *   // needs a workaround related to state isolation. Merge fix to current branch later.
     */
    private void insertPilotExecutionWrapper(Body body, Trap trap, SootMethod extractedMethod,
                                             List<Unit> originalCatchUnits) {
        LocalGeneratorUtil lg = new LocalGeneratorUtil(body);
        Chain<Unit> units = body.getUnits();
        Unit handlerUnit = trap.getHandlerUnit();

        // Find the caught exception local
        Local caughtExceptionLocal = null;
        for (Unit unit : originalCatchUnits) {
            if (unit instanceof IdentityStmt) {
                IdentityStmt stmt = (IdentityStmt) unit;
                if (stmt.getRightOp() instanceof CaughtExceptionRef) {
                    caughtExceptionLocal = (Local) stmt.getLeftOp();
                    break;
                }
            }
        }

        // Get the parameter locals from the extracted method
        List<Local> methodArgs = new ArrayList<>();
        Tag tag = extractedMethod.getTag("ParameterLocalsTag");
        if (tag instanceof ParameterLocalsTag) {
            methodArgs = ((ParameterLocalsTag) tag).getParamLocals();
        }

        // Build actual arguments list - map parameter locals to actual locals in body
        List<Local> actualArgs = new ArrayList<>();
        for (Local paramLocal : methodArgs) {
            if (caughtExceptionLocal != null && paramLocal.getType().equals(caughtExceptionLocal.getType())
                    && actualArgs.isEmpty()) {
                // First parameter of matching type is the exception
                actualArgs.add(caughtExceptionLocal);
            } else {
                // Other parameters remain the same
                actualArgs.add(paramLocal);
            }
        }

        // Create transformation units
        List<Unit> transformedUnits = new ArrayList<>();

        // 1. Create runnable that calls the extracted method
        Map.Entry<Local, List<Unit>> runnableResult = createMethodCallRunnable(
                body, lg, extractedMethod, actualArgs, body.getMethod().getDeclaringClass()
        );
        transformedUnits.addAll(runnableResult.getValue());
        Local runnableLocal = runnableResult.getKey();

        // 2. Context ctx = PilotUtil.start(runnable)
        SootClass pilotUtilClass = Scene.v().loadClassAndSupport("org.pilot.PilotUtil");
        SootMethod startMethod = pilotUtilClass.getMethod(
                "io.opentelemetry.context.Context start(java.lang.Runnable)");

        Local contextLocal = lg.generateLocal(
                Scene.v().loadClassAndSupport("io.opentelemetry.context.Context").getType());
        StaticInvokeExpr startCall = Jimple.v().newStaticInvokeExpr(
                startMethod.makeRef(), runnableLocal);
        transformedUnits.add(Jimple.v().newAssignStmt(contextLocal, startCall));

        // 3. PilotUtil.waitUntilPilotExecutionFinished(ctx)
        SootMethod waitMethod = pilotUtilClass.getMethod(
                "org.pilot.STATUS waitUntilPilotExecutionFinished(io.opentelemetry.context.Context)");
        StaticInvokeExpr waitCall = Jimple.v().newStaticInvokeExpr(
                waitMethod.makeRef(), contextLocal);
        Local statusLocal = lg.generateLocal(
                Scene.v().loadClassAndSupport("org.pilot.STATUS").getType());
        transformedUnits.add(Jimple.v().newAssignStmt(statusLocal, waitCall));

        // Insert all transformation units after handler
        Unit insertPoint = handlerUnit;
        for (Unit unit : transformedUnits) {
            units.insertAfter(unit, insertPoint);
            insertPoint = unit;
        }

        // Keep original catch units - they will execute after pilot execution finishes
    }

    /**
     * Create a runnable that calls the extracted method
     */
    private Map.Entry<Local, List<Unit>> createMethodCallRunnable(Body body, LocalGeneratorUtil lg,
                                                                  SootMethod methodToCall,
                                                                  List<Local> methodArgs,
                                                                  SootClass containingClass) {
        List<Unit> creationUnits = new ArrayList<>();

        // Create runnable class
        String className = containingClass.getName() + "$MethodCallRunnable" + methodCounter.getAndIncrement();
        SootClass runnableClass = new SootClass(className, Modifier.PUBLIC);
        Scene.v().addClass(runnableClass);
        runnableClass.setApplicationClass();
        runnableClass.addInterface(Scene.v().loadClassAndSupport("java.lang.Runnable"));
        runnableClass.setSuperclass(Scene.v().loadClassAndSupport("java.lang.Object"));

        // Add fields for method arguments
        List<SootField> argFields = new ArrayList<>();
        for (int i = 0; i < methodArgs.size(); i++) {
            SootField field = new SootField("arg" + i, methodArgs.get(i).getType(),
                    Modifier.PRIVATE | Modifier.FINAL);
            runnableClass.addField(field);
            argFields.add(field);
        }

        // Add field for containing instance if needed (only for non-static methods)
        SootField instanceField = null;
        if (!methodToCall.isStatic() && !body.getMethod().isStatic()) {
            instanceField = new SootField("instance", containingClass.getType(),
                    Modifier.PRIVATE | Modifier.FINAL);
            runnableClass.addField(instanceField);
        }

        // Create constructor
        createRunnableConstructorWithArgs(runnableClass, argFields, instanceField);

        // Create run method that calls the extracted method
        createRunMethodWithCall(runnableClass, argFields, instanceField, methodToCall);

        // Create instance
        Local runnableLocal = lg.generateLocal(runnableClass.getType());

        // new MethodCallRunnable()
        NewExpr newExpr = Jimple.v().newNewExpr(runnableClass.getType());
        creationUnits.add(Jimple.v().newAssignStmt(runnableLocal, newExpr));

        // Prepare constructor arguments
        List<Value> constructorArgs = new ArrayList<>(methodArgs);
        if (instanceField != null && body.getThisLocal() != null) {
            constructorArgs.add(body.getThisLocal());
        }

        // Call constructor
        SootMethod constructor = runnableClass.getMethodByName("<init>");
        SpecialInvokeExpr constructorCall = Jimple.v().newSpecialInvokeExpr(
                runnableLocal, constructor.makeRef(), constructorArgs);
        creationUnits.add(Jimple.v().newInvokeStmt(constructorCall));

        return new AbstractMap.SimpleEntry<>(runnableLocal, creationUnits);
    }

    /**
     * Create constructor for runnable with arguments
     */
    private void createRunnableConstructorWithArgs(SootClass runnableClass, List<SootField> argFields,
                                                   SootField instanceField) {
        List<Type> paramTypes = new ArrayList<>();
        for (SootField field : argFields) {
            paramTypes.add(field.getType());
        }
        if (instanceField != null) {
            paramTypes.add(instanceField.getType());
        }

        SootMethod constructor = new SootMethod("<init>", paramTypes, VoidType.v(), Modifier.PUBLIC);
        runnableClass.addMethod(constructor);

        Body constructorBody = Jimple.v().newBody(constructor);
        constructor.setActiveBody(constructorBody);

        // Add locals
        Local thisLocal = Jimple.v().newLocal("this", runnableClass.getType());
        constructorBody.getLocals().add(thisLocal);

        List<Local> paramLocals = new ArrayList<>();
        for (int i = 0; i < paramTypes.size(); i++) {
            Local paramLocal = Jimple.v().newLocal("param" + i, paramTypes.get(i));
            constructorBody.getLocals().add(paramLocal);
            paramLocals.add(paramLocal);
        }

        // Identity statements
        constructorBody.getUnits().add(Jimple.v().newIdentityStmt(thisLocal,
                Jimple.v().newThisRef(runnableClass.getType())));
        for (int i = 0; i < paramLocals.size(); i++) {
            constructorBody.getUnits().add(Jimple.v().newIdentityStmt(paramLocals.get(i),
                    Jimple.v().newParameterRef(paramTypes.get(i), i)));
        }

        // Call super
        SootMethod superConstructor = Scene.v().loadClassAndSupport("java.lang.Object")
                .getMethod("void <init>()");
        constructorBody.getUnits().add(Jimple.v().newInvokeStmt(
                Jimple.v().newSpecialInvokeExpr(thisLocal, superConstructor.makeRef())));

        // Assign fields
        for (int i = 0; i < argFields.size(); i++) {
            FieldRef fieldRef = Jimple.v().newInstanceFieldRef(thisLocal, argFields.get(i).makeRef());
            constructorBody.getUnits().add(Jimple.v().newAssignStmt(fieldRef, paramLocals.get(i)));
        }
        if (instanceField != null) {
            FieldRef fieldRef = Jimple.v().newInstanceFieldRef(thisLocal, instanceField.makeRef());
            constructorBody.getUnits().add(Jimple.v().newAssignStmt(fieldRef,
                    paramLocals.get(paramLocals.size() - 1)));
        }

        constructorBody.getUnits().add(Jimple.v().newReturnVoidStmt());
    }

    /**
     * Create run method that calls the extracted method
     */
    private void createRunMethodWithCall(SootClass runnableClass, List<SootField> argFields,
                                         SootField instanceField, SootMethod methodToCall) {
        SootMethod runMethod = new SootMethod("run", Collections.emptyList(), VoidType.v(), Modifier.PUBLIC);
        runnableClass.addMethod(runMethod);

        Body runBody = Jimple.v().newBody(runMethod);
        runMethod.setActiveBody(runBody);

        // Add this local
        Local thisLocal = Jimple.v().newLocal("this", runnableClass.getType());
        runBody.getLocals().add(thisLocal);
        runBody.getUnits().add(Jimple.v().newIdentityStmt(thisLocal,
                Jimple.v().newThisRef(runnableClass.getType())));

        // Load arguments from fields
        List<Local> argLocals = new ArrayList<>();
        for (SootField field : argFields) {
            Local argLocal = Jimple.v().newLocal("arg", field.getType());
            runBody.getLocals().add(argLocal);
            FieldRef fieldRef = Jimple.v().newInstanceFieldRef(thisLocal, field.makeRef());
            runBody.getUnits().add(Jimple.v().newAssignStmt(argLocal, fieldRef));
            argLocals.add(argLocal);
        }

        // Load instance if needed
        Local instanceLocal = null;
        if (instanceField != null) {
            instanceLocal = Jimple.v().newLocal("instance", instanceField.getType());
            runBody.getLocals().add(instanceLocal);
            FieldRef fieldRef = Jimple.v().newInstanceFieldRef(thisLocal, instanceField.makeRef());
            runBody.getUnits().add(Jimple.v().newAssignStmt(instanceLocal, fieldRef));
        }

        // Call the extracted method
        InvokeExpr methodCall;
        if (methodToCall.isStatic()) {
            methodCall = Jimple.v().newStaticInvokeExpr(methodToCall.makeRef(), argLocals);
        } else {
            methodCall = Jimple.v().newVirtualInvokeExpr(instanceLocal, methodToCall.makeRef(), argLocals);
        }
        runBody.getUnits().add(Jimple.v().newInvokeStmt(methodCall));

        runBody.getUnits().add(Jimple.v().newReturnVoidStmt());
    }

    /**
     * Copy units with local mapping - special handling for catch block
     */
    private void copyUnitsWithMapping(List<Unit> units, Body targetBody, Map<Local, Local> localMapping) {
        LocalGeneratorUtil lg = new LocalGeneratorUtil(targetBody);
        Map<Unit, Unit> unitMapping = new HashMap<>();
        Set<Unit> catchBlockUnits = new HashSet<>(units);

        // First, collect all locals that are defined in the catch block
        Set<Local> definedInCatch = new HashSet<>();
        for (Unit unit : units) {
            for (ValueBox defBox : unit.getDefBoxes()) {
                if (defBox.getValue() instanceof Local) {
                    definedInCatch.add((Local) defBox.getValue());
                }
            }
        }

        // First pass: clone all units and create mapping
        List<Unit> clonedUnits = new ArrayList<>();
        for (Unit unit : units) {
            // Skip identity statements from original catch block
            if (unit instanceof IdentityStmt) {
                IdentityStmt stmt = (IdentityStmt) unit;
                if (stmt.getRightOp() instanceof CaughtExceptionRef) {
                    continue;
                }
            }

            if (unit instanceof ReturnStmt) {
                // Replace any return statement with return void
                Unit returnVoidStmt = Jimple.v().newReturnVoidStmt();
                unitMapping.put(unit, returnVoidStmt);
                clonedUnits.add(returnVoidStmt);
                continue;
            }

            Unit clonedUnit = (Unit) unit.clone();
            unitMapping.put(unit, clonedUnit);
            clonedUnits.add(clonedUnit);

            // Update all value boxes - but handle special cases
            for (ValueBox vb : clonedUnit.getUseAndDefBoxes()) {
                Value value = vb.getValue();
                if (value == null) {
                    LOG.warn("Found null value in ValueBox while cloning unit: {}", unit);
                    continue;
                }

                if (value instanceof Local) {
                    Local local = (Local) value;

                    // For locals not defined in catch block, create new locals in target body
                    // These will be initialized through field access/static calls in the catch block itself
                    Local mapped = localMapping.get(local);
                    if (mapped == null) {
                        // Always create a new local in the target body
                        mapped = lg.generateLocal(local.getType());
                        localMapping.put(local, mapped);

                        // If this local is not defined in catch, it needs to be added to the body
                        if (!definedInCatch.contains(local)) {
                            // Make sure the local is added to the target body
                            if (!targetBody.getLocals().contains(mapped)) {
                                targetBody.getLocals().add(mapped);
                            }
                        }
                    }
                    vb.setValue(mapped);
                }
            }
        }

        // Second pass: fix jump targets
        List<Unit> finalUnits = new ArrayList<>();
        for (int i = 0; i < clonedUnits.size(); i++) {
            Unit clonedUnit = clonedUnits.get(i);

            if (clonedUnit instanceof GotoStmt) {
                GotoStmt gotoStmt = (GotoStmt) clonedUnit;
                Unit originalTarget = gotoStmt.getTarget();

                Unit mappedTarget = unitMapping.get(originalTarget);
                if (mappedTarget != null) {
                    // Jump within catch block - update to cloned target
                    gotoStmt.setTarget(mappedTarget);
                    finalUnits.add(clonedUnit);
                } else {
                    // Jump outside catch block - replace with return
                    finalUnits.add(Jimple.v().newReturnVoidStmt());
                }
            } else if (clonedUnit instanceof IfStmt) {
                IfStmt ifStmt = (IfStmt) clonedUnit;
                Unit originalTarget = ifStmt.getTarget();

                Unit mappedTarget = unitMapping.get(originalTarget);
                if (mappedTarget != null) {
                    // Jump within catch block - update to cloned target
                    ifStmt.setTarget(mappedTarget);
                }
                finalUnits.add(clonedUnit);
            } else {
                finalUnits.add(clonedUnit);
            }
        }

        // Third pass: fix if statements that jump outside
        for (int i = 0; i < finalUnits.size(); i++) {
            Unit unit = finalUnits.get(i);
            if (unit instanceof IfStmt) {
                IfStmt ifStmt = (IfStmt) unit;
                Unit target = ifStmt.getTarget();

                // Check if target is within our cloned units
                boolean targetInBlock = false;
                for (Unit u : finalUnits) {
                    if (u == target) {
                        targetInBlock = true;
                        break;
                    }
                }

                if (!targetInBlock) {
                    // Add a return as the jump target
                    ReturnVoidStmt returnStmt = Jimple.v().newReturnVoidStmt();
                    // Insert after current position
                    finalUnits.add(i + 1, returnStmt);
                    ifStmt.setTarget(returnStmt);
                    i++; // Skip the newly added return
                }
            }
        }

        // Add all units to target body
        for (Unit unit : finalUnits) {
            targetBody.getUnits().add(unit);
        }
    }

    /**
     * Check if a local is defined within the given units
     */
    private boolean isLocalDefinedInUnits(Local local, List<Unit> units) {
        for (Unit unit : units) {
            for (ValueBox defBox : unit.getDefBoxes()) {
                if (defBox.getValue() instanceof Local && defBox.getValue().equals(local)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Collect all locals used in the units
     */
    private Set<Local> collectUsedLocals(List<Unit> units) {
        Set<Local> locals = new HashSet<>();
        for (Unit unit : units) {
            for (ValueBox vb : unit.getUseAndDefBoxes()) {
                if (vb.getValue() instanceof Local) {
                    locals.add((Local) vb.getValue());
                }
            }
        }
        return locals;
    }

    /**
     * Check if a local is the 'this' local
     */
    private boolean isThisLocal(Local local, Body body) {
        // For static methods, there is no 'this' local
        if (body.getMethod().isStatic()) {
            return false;
        }

        // Check if body has a this local (defensive programming)
        try {
            Local thisLocal = body.getThisLocal();
            return thisLocal != null && thisLocal.equals(local);
        } catch (RuntimeException e) {
            // If getThisLocal() throws exception, it means this is a static method
            return false;
        }
    }

    /**
     * Check if units need 'this' local
     */
    private boolean needsThisLocal(List<Unit> units, Body body) {
        // Static methods never need 'this' local
        if (body.getMethod().isStatic()) {
            return false;
        }

        // Check if body has a this local
        Local thisLocal = null;
        try {
            thisLocal = body.getThisLocal();
        } catch (RuntimeException e) {
            // Static method
            return false;
        }

        if (thisLocal == null) return false;

        for (Unit unit : units) {
            for (ValueBox vb : unit.getUseBoxes()) {
                if (vb.getValue() instanceof Local && thisLocal.equals(vb.getValue())) {
                    return true;
                }
                if (vb.getValue() instanceof InstanceFieldRef || vb.getValue() instanceof InstanceInvokeExpr) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Ensure method ends with return statement
     */
    private void ensureMethodReturns(Body body) {
        Chain<Unit> units = body.getUnits();
        if (units.isEmpty() || !(units.getLast() instanceof ReturnStmt || units.getLast() instanceof ReturnVoidStmt)) {
            units.add(Jimple.v().newReturnVoidStmt());
        }
    }

    /**
     * Get ordered catch units from the catch block
     */
    private List<Unit> getOrderedCatchUnits(Set<Unit> catchBlockUnits, Body body, Unit handlerUnit) {
        List<Unit> orderedUnits = new ArrayList<>();
        boolean foundHandler = false;

        for (Unit unit : body.getUnits()) {
            if (unit.equals(handlerUnit)) {
                foundHandler = true;
                orderedUnits.add(unit); // Include handler unit
            } else if (foundHandler && catchBlockUnits.contains(unit)) {
                orderedUnits.add(unit);
            }
        }

        return orderedUnits;
    }
}