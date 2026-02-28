package edu.uva.liftlab.pilot.generator;

import edu.uva.liftlab.pilot.isolation.stateredirection.ClassFilterHelper;
import edu.uva.liftlab.pilot.util.LocalGeneratorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;
import soot.util.Chain;

import java.util.*;

import static edu.uva.liftlab.pilot.util.Constants.*;
import static edu.uva.liftlab.pilot.util.SootUtils.originalMethodShouldBeInstrumented;
import static edu.uva.liftlab.pilot.util.SootUtils.printLog4j;

public class FastForwardMethodGenerator {
    private static final Logger LOG = LoggerFactory.getLogger(FastForwardMethodGenerator.class);

    public Map<Unit, Unit> originalToCloneMap = new HashMap<>();

    private Map<Unit, Unit> shadowToOriginalMap = new HashMap<>();

    public Map<SootMethod, List<Local>> methodToLocalsMap = new HashMap<>();

    public Map<SootMethod, Map<Unit, String>> methodUnitIds = new HashMap<>();

    public List<SootMethod> fastForwardMethods = new ArrayList<>();

    public HashMap<Unit, Unit> divergeToGotoMap = new HashMap<>();


//    public void processClasses(ClassFilterHelper filter) {
//        for (SootClass sc : Scene.v().getApplicationClasses()) {
//            if (filter.shouldSkip(sc)) continue;
//            List<SootMethod> methods = new ArrayList<>(sc.getMethods());
//            for (SootMethod method : methods) {
//                if (!method.getName().endsWith(INSTRUMENTATION_SUFFIX)) {
//                    this.addFastForwardMethod(method, sc);
//                    //this.addDryRunDivergeCode2OriginalFunc(method, sc, filter);
//                }
//            }
//        }
//    }


    public void addFastForwardMethod(SootMethod originalMethod, SootClass sootClass, Map<Unit, String> unitStringMap) {
        if(!originalMethodShouldBeInstrumented(originalMethod, sootClass)) {
            return;
        }

        String instrumentationMethodName = originalMethod.getName() + SHADOW;
        SootMethod instrumentationMethod = new SootMethod(
                instrumentationMethodName,
                originalMethod.getParameterTypes(),
                originalMethod.getReturnType(),
                originalMethod.getModifiers()
        );
        try{
            sootClass.addMethod(instrumentationMethod);
        } catch(RuntimeException e) {
            LOG.info("Method {} already exists in class {}", instrumentationMethodName, sootClass.getName());
            return;
        }

        Body originalBody = originalMethod.retrieveActiveBody();
        Body instrumentationBody = Jimple.v().newBody(instrumentationMethod);
        instrumentationBody.importBodyContentsFrom(originalBody);
        instrumentationMethod.setActiveBody(instrumentationBody);

        Chain<Unit> originalUnits = originalBody.getUnits();
        Chain<Unit> clonedUnits = instrumentationBody.getUnits();

        Iterator<Unit> originalIt = originalUnits.iterator();
        Iterator<Unit> clonedIt = clonedUnits.iterator();

        while (originalIt.hasNext() && clonedIt.hasNext()) {
            Unit originalUnit = originalIt.next();
            Unit clonedUnit = clonedIt.next();
            originalToCloneMap.put(originalUnit, clonedUnit);
        }

        fastForwardMethods.add(instrumentationMethod);
        Map<Unit, String> unitIds = new HashMap<>();
        for (Map.Entry<Unit, String> entry : unitStringMap.entrySet()) {
            Unit originalUnit = entry.getKey();
            Unit clonedUnit = originalToCloneMap.get(originalUnit);
            LOG.info("entry.getValue() = " + entry.getValue());
            unitIds.put(clonedUnit, entry.getValue());
        }
        methodUnitIds.put(instrumentationMethod, unitIds);
    }


    public void replaceWithFastForward() {
        for (Map.Entry<SootMethod, Map<Unit, String>> entry : methodUnitIds.entrySet()) {
            SootMethod shadowMethod = entry.getKey();
            Body body = shadowMethod.getActiveBody();
            Chain<Unit> units = body.getUnits();
            Map<Unit, Unit> replacements = new HashMap<>();

            // Iterate through all units
            for (Unit unit : units) {
                if (unit instanceof InvokeStmt) {
                    InvokeStmt invokeStmt = (InvokeStmt) unit;
                    replaceInvokeExpr(invokeStmt, replacements);
                } else if (unit instanceof AssignStmt) {
                    AssignStmt assignStmt = (AssignStmt) unit;
                    Value rightOp = assignStmt.getRightOp();
                    if (rightOp instanceof InvokeExpr) {
                        replaceInvokeExpr(assignStmt, replacements);
                    }
                }
            }

            // Apply all replacements
            for (Map.Entry<Unit, Unit> replacement : replacements.entrySet()) {
                units.swapWith(replacement.getKey(), replacement.getValue());
            }
        }
    }

    private void replaceInvokeExpr(Stmt stmt, Map<Unit, Unit> replacements) {
        InvokeExpr invokeExpr = stmt.getInvokeExpr();
        SootMethod calledMethod = invokeExpr.getMethod();
        String shadowMethodName = calledMethod.getName() + SHADOW;

        try {
            // Try to get the shadow version of the method
            SootMethod shadowMethod = calledMethod.getDeclaringClass().getMethod(
                    shadowMethodName,
                    calledMethod.getParameterTypes(),
                    calledMethod.getReturnType()
            );

            // Create new invoke expression for the shadow method
            InvokeExpr newInvokeExpr;
            if (invokeExpr instanceof SpecialInvokeExpr) {
                Local base = (Local) ((SpecialInvokeExpr) invokeExpr).getBase();
                newInvokeExpr = Jimple.v().newSpecialInvokeExpr(
                        base,
                        shadowMethod.makeRef(),
                        invokeExpr.getArgs()
                );
            } else if (invokeExpr instanceof VirtualInvokeExpr) {
                Local base = (Local) ((VirtualInvokeExpr) invokeExpr).getBase();
                newInvokeExpr = Jimple.v().newVirtualInvokeExpr(
                        base,
                        shadowMethod.makeRef(),
                        invokeExpr.getArgs()
                );
            } else if (invokeExpr instanceof StaticInvokeExpr) {
                newInvokeExpr = Jimple.v().newStaticInvokeExpr(
                        shadowMethod.makeRef(),
                        invokeExpr.getArgs()
                );
            } else {
                // Skip other types of invoke expressions
                return;
            }

            // Create replacement statement
            Unit replacement;
            if (stmt instanceof InvokeStmt) {
                replacement = Jimple.v().newInvokeStmt(newInvokeExpr);
            } else if (stmt instanceof AssignStmt) {
                replacement = Jimple.v().newAssignStmt(
                        ((AssignStmt) stmt).getLeftOp(),
                        newInvokeExpr
                );
            } else {
                return;
            }

            replacements.put(stmt, replacement);
        } catch (RuntimeException e) {
            // Shadow method doesn't exist, keep original invoke
        }
    }

    public void addDryRunDivergeCode2OriginalFunc(SootMethod originalMethod, SootClass sc, ClassFilterHelper filter) {
        if(!originalMethodShouldBeInstrumented(originalMethod, sc) || filter.getStartingPoints().contains(originalMethod.getSignature())) {
            return;
        }

        String instrumentationMethodName = originalMethod.getName() + INSTRUMENTATION_SUFFIX;
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
                        Scene.v().loadClassAndSupport("org.apache.cassandra.utils.dryrun.TraceUtil"),
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
