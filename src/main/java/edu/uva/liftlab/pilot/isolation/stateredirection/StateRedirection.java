package edu.uva.liftlab.pilot.isolation.stateredirection;

import edu.uva.liftlab.pilot.util.LocalGeneratorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static edu.uva.liftlab.pilot.util.Constants.INSTRUMENTATION_SUFFIX;
import static edu.uva.liftlab.pilot.util.SootUtils.*;

public class StateRedirection {
    private static final Logger LOG = LoggerFactory.getLogger(StateRedirection.class);

    private final SootClass sootClass;
    private final FieldAccessProcessor fieldAccessProcessor;
    private SootMethod currentMethod;

    public StateRedirection(SootClass sootClass) {
        this.sootClass = sootClass;
        this.fieldAccessProcessor = new FieldAccessProcessor();
    }




    public static void redirectAllClassesStates(ClassFilterHelper filter) {
        boolean isStateBlackWhiteListEnabled = filter.isStateBlackWhiteListEnabled();
        for (SootClass sc : Scene.v().getApplicationClasses()) {
            if (filter.shouldSkip(sc)) {
                continue;
            }

            if(isStateBlackWhiteListEnabled){
                boolean isStateIsolationBlackListClass = filter.isStateBlackListClass(sc) && !filter.isStateWhiteListClass(sc);
                if (isStateIsolationBlackListClass) {
                    LOG.info("Skipping state redirection for blacklisted class: {}", sc.getName());
                    continue;
                }
            }
            LOG.info("StateRedirection Processing class: {}", sc.getName());
            new StateRedirection(sc).redirectStatesInFunc$instrumentation();
        }
    }

    public void redirectStatesInFunc$instrumentation() {

        for (SootMethod method : sootClass.getMethods()) {
            LOG.debug("Method instrumentation: {}", method.getName());
            if (!shouldInstrumentMethod(method)) {
                continue;
            }
            currentMethod = method;
            redirectMethod(method);
        }
    }

    private void redirectMethod(SootMethod method) {
        try {
            if (!method.hasActiveBody()) {
                LOG.info("Method {} has no active body, skipping", method.getName());
                return;
            }

            Body originalBody = method.retrieveActiveBody();
            Body instrumentationBody = Jimple.v().newBody(method);
            instrumentationBody.importBodyContentsFrom(originalBody);


            Local pilotIdLocal = insertGetPilotIdAtMethodStart(instrumentationBody);


            redirectFieldAccesses(instrumentationBody, pilotIdLocal);

            method.setActiveBody(instrumentationBody);



            LOG.info("Successfully redirected method: {}", method.getName());
        } catch (Exception e) {
            LOG.error("Failed to redirect method {}: {}", method.getName(), e.getMessage());
            if (LOG.isDebugEnabled()) {
                LOG.debug("Stack trace:", e);
            }
            throw e;
        }
    }

    private Local insertGetPilotIdAtMethodStart(Body body) {

        LocalGeneratorUtil lg = new LocalGeneratorUtil(body);
        Local pilotIdLocal = lg.generateLocal(IntType.v());

        SootMethodRef getPilotIdRef = Scene.v().makeMethodRef(
                Scene.v().getSootClass("org.pilot.PilotUtil"),
                "getPilotID",
                Collections.emptyList(),
                IntType.v(),
                true
        );

        Stmt getPilotIdStmt = Jimple.v().newAssignStmt(
                pilotIdLocal,
                Jimple.v().newStaticInvokeExpr(getPilotIdRef)
        );

        UnitPatchingChain units = body.getUnits();

        Unit lastIdentityUnit = getLastIdentityUnit(body);
        Unit firstNonIdentityUnit = getFirstNonIdentityStmt(body);

        if(lastIdentityUnit !=null){
            units.insertAfter(getPilotIdStmt, lastIdentityUnit);
        } else{
            if (firstNonIdentityUnit != null) {
                units.insertBefore(getPilotIdStmt, firstNonIdentityUnit);
            } else {
                units.add(getPilotIdStmt);
            }
        }

        body.validate();

        return pilotIdLocal;
    }

    private void redirectFieldAccesses(Body body, Local pilotIdLocal) {
        UnitPatchingChain units = body.getUnits();
        List<Unit> originalUnits = new ArrayList<>(units);
        LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

        for (Unit unit : originalUnits) {
            if (unit instanceof AssignStmt) {
                fieldAccessProcessor.handleAssignStmt((AssignStmt) unit, units, lg,
                        currentMethod, pilotIdLocal);
            }
            if (unit instanceof InvokeStmt ||
                    (unit instanceof AssignStmt && ((AssignStmt)unit).getRightOp() instanceof InvokeExpr)) {
                fieldAccessProcessor.handleInvokeStmt(unit, units, lg,
                        currentMethod, pilotIdLocal);
            }
        }
    }

    private boolean shouldInstrumentMethod(SootMethod method) {
        if (method.isAbstract() || method.isNative()) {
            LOG.debug("Skipping abstract or native method: {}", method.getName());
            return false;
        }

        if (!method.getName().endsWith(INSTRUMENTATION_SUFFIX)) {
            LOG.debug("Skipping already instrumented method: {}", method.getName());
            return false;
        }

        if (!method.hasActiveBody()) {
            LOG.debug("Skipping method without active body: {}", method.getName());
            return false;
        }

        return true;
    }

    public SootClass getSootClass() {
        return sootClass;
    }

    public SootMethod getCurrentMethod() {
        return currentMethod;
    }

    /**

     */
    protected FieldAccessProcessor getFieldAccessProcessor() {
        return fieldAccessProcessor;
    }

}
