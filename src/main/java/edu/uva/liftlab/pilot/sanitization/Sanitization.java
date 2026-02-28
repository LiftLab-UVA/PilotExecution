package edu.uva.liftlab.pilot.sanitization;

import edu.uva.liftlab.pilot.util.LocalGeneratorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static edu.uva.liftlab.pilot.util.Constants.INSTRUMENTATION_SUFFIX;

public class Sanitization {
    private static final Logger LOG = LoggerFactory.getLogger(Sanitization.class);
    private static final String SYSTEM_CLASS = "java.lang.System";

    private final SootClass sootClass;
    private SootMethod currentMethod;

    public Sanitization(SootClass sootClass) {
        this.sootClass = sootClass;
    }

    public static void sanitizeAllClasses() {
        for (SootClass sc : Scene.v().getApplicationClasses()) {
            LOG.info("Processing class for sanization: {}", sc.getName());
            new Sanitization(sc).sanitize();
        }
    }

    private void sanitize() {
        // The users could customize this class to report runtime anomalies they are interested in(e.g. some fatal runtime exception) with a customized report API.
        // Here we just show a basic way to insert report APIs.
        // We directly insert the report API before the injected fault in the AE example to make the code more understandable.
        for (SootMethod method : sootClass.getMethods()) {
            if (!shouldInstrumentMethod(method)) {
                continue;
            }
            currentMethod = method;
            sanitizeMethod(method);
        }
    }

    private void sanitizeMethod(SootMethod method) {
        try {
            if (!method.hasActiveBody()) {
                return;
            }

            Body originalBody = method.retrieveActiveBody();
            Body instrumentationBody = Jimple.v().newBody(method);
            instrumentationBody.importBodyContentsFrom(originalBody);

            sanitizeDangerousCalls(instrumentationBody);

            instrumentationBody.validate();
            method.setActiveBody(instrumentationBody);

            LOG.debug("Successfully sanitized method: {}", method.getName());
        } catch (Exception e) {
            LOG.error("Failed to sanitize method {}: {}", method.getName(), e.getMessage());
        }
    }

    private void sanitizeDangerousCalls(Body body) {
        UnitPatchingChain units = body.getUnits();
        List<Unit> originalUnits = new ArrayList<>(units);
        LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

        for (Unit unit : originalUnits) {
            if (unit instanceof InvokeStmt) {
                InvokeExpr expr = ((InvokeStmt) unit).getInvokeExpr();
                if (isDangerousCall(expr)) {
                    replaceDangerousCall(unit, units, expr);
                }
            }
        }
    }

    private boolean isDangerousCall(InvokeExpr expr) {
        SootMethod method = expr.getMethod();
        return method.getDeclaringClass().getName().equals(SYSTEM_CLASS)
                && method.getName().equals("exit");
    }

    private void replaceDangerousCall(Unit unit, UnitPatchingChain units, InvokeExpr expr) {
        LocalGeneratorUtil lg = new LocalGeneratorUtil(currentMethod.getActiveBody());


        Local exceptionLocal = lg.generateLocal(RefType.v("java.lang.Exception"));


        NewExpr newExceptionExpr = Jimple.v().newNewExpr(RefType.v("java.lang.Exception"));
        AssignStmt assignNewStmt = Jimple.v().newAssignStmt(exceptionLocal, newExceptionExpr);


        SootMethodRef exceptionInitRef = Scene.v().makeMethodRef(
                Scene.v().getSootClass("java.lang.Exception"),
                "<init>",
                Collections.singletonList(RefType.v("java.lang.String")),
                VoidType.v(),
                false
        );

        SpecialInvokeExpr initInvokeExpr = Jimple.v().newSpecialInvokeExpr(
                exceptionLocal,
                exceptionInitRef,
                StringConstant.v("fatal call intercepted in pilot mode")
        );
        InvokeStmt initStmt = Jimple.v().newInvokeStmt(initInvokeExpr);

        SootMethodRef errorMethodRef = Scene.v().makeMethodRef(
                Scene.v().getSootClass("org.pilot.PilotUtil"),
                "error",
                Collections.singletonList(RefType.v("java.lang.Exception")),
                VoidType.v(),
                true
        );

        StaticInvokeExpr errorInvokeExpr = Jimple.v().newStaticInvokeExpr(errorMethodRef, exceptionLocal);
        InvokeStmt errorStmt = Jimple.v().newInvokeStmt(errorInvokeExpr);

        units.insertBefore(assignNewStmt, unit);
        units.insertBefore(initStmt, unit);
        units.insertBefore(errorStmt, unit);
        units.remove(unit);
    }

    private boolean shouldInstrumentMethod(SootMethod method) {
        return method.getName().endsWith(INSTRUMENTATION_SUFFIX);

    }
}
