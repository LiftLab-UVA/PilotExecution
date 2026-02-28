package edu.uva.liftlab.pilot.distributedtracing;

import edu.uva.liftlab.pilot.isolation.stateredirection.ClassFilterHelper;
import edu.uva.liftlab.pilot.util.LocalGeneratorUtil;
import edu.uva.liftlab.pilot.util.SootUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;

import java.util.Arrays;
import java.util.Collections;

import static edu.uva.liftlab.pilot.util.Constants.CONTEXT_TYPE;
import static edu.uva.liftlab.pilot.util.SootUtils.classShouldBeInstrumented;

public class ThreadTracer {

    public ClassFilterHelper filter;

    public SootClass sootClass;

    private static final Logger LOG = LoggerFactory.getLogger(ThreadTracer.class);
    private static final String CONTEXT_FIELD_NAME = "PilotContext";
    private static final String THREAD_CLASS_NAME = "java.lang.Thread";

    private boolean shouldBeInstrumented(SootClass sc) {
        // Check if the class is an interface or a phantom class
        if (sc.isInterface() || sc.isPhantom() || SootUtils.isDryRunClass(sc) || filter.shouldSkip(sc)) {
            return false;
        }

        return isThreadOrSubclass(sc);
    }

    private boolean isThreadOrSubclass(SootClass cls) {
        // Check if this is Thread class itself
        if (cls.getName().equals(THREAD_CLASS_NAME)) {
            return true;
        }

        // Check superclass
        if (cls.hasSuperclass()) {
            return isThreadOrSubclass(cls.getSuperclass());
        }

        return false;
    }


    public void instrument() {
        if(!isThreadOrSubclass(this.sootClass)) {
            return;
        }
        addOpenTelemetryContextField(this.sootClass);
        wrapRunMethod(this.sootClass);
    }

    private void addOpenTelemetryContextField(SootClass sootClass) {
        if (!classShouldBeInstrumented(sootClass)) {
            return;
        }

        String fieldName = getContextFieldName(sootClass);
        RefType contextType = RefType.v(CONTEXT_TYPE);

        // Check if field already exists
        if (!sootClass.declaresField(fieldName, contextType)) {
            LOG.info("Adding OpenTelemetry Context field to {}", sootClass.getName());
            SootField field = new SootField(fieldName, contextType, Modifier.PUBLIC);
            sootClass.addField(field);
        }
    }



    private String getContextFieldName(SootClass sootClass) {
        return CONTEXT_FIELD_NAME;
    }


    public void setThreadContext(SootClass threadClass, Local threadLocal, PatchingChain<Unit> units, Unit insertPoint) {
        if (!shouldBeInstrumented(threadClass)) {
            return;
        }

        LOG.info("Setting thread context for {}", threadClass.getName());

        try {
            // Get Context.current() method
            SootMethodRef contextCurrentMethodRef = Scene.v().makeMethodRef(
                    Scene.v().loadClassAndSupport("io.opentelemetry.context.Context"),
                    "current",
                    Collections.emptyList(),
                    RefType.v("io.opentelemetry.context.Context"),
                    true
            );

            // Create Context.current() call
            StaticInvokeExpr contextCurrentInvoke = Jimple.v().newStaticInvokeExpr(contextCurrentMethodRef);

            // Get context field reference
            FieldRef contextFieldRef = Jimple.v().newInstanceFieldRef(
                    threadLocal,
                    threadClass.getField(getContextFieldName(threadClass),
                            RefType.v("io.opentelemetry.context.Context")).makeRef()
            );

            // Create assignment: thread.contextField = Context.current()
            AssignStmt assignStmt = Jimple.v().newAssignStmt(contextFieldRef, contextCurrentInvoke);

            // Insert the statement
            if (insertPoint != null) {
                units.insertAfter(assignStmt, insertPoint);
            } else {
                units.addFirst(assignStmt);
            }
        } catch (Exception e) {
            LOG.error("Error setting thread context for class {}: {}", threadClass.getName(), e.getMessage(), e);
        }
    }


    private void wrapRunMethod(SootClass threadClass) {
        if (!threadClass.declaresMethod("void run()")) {
            LOG.info("Class {} does not declare run() method, skipping", threadClass.getName());
            return;
        }

        SootMethod runMethod = threadClass.getMethod("void run()");
        if (!runMethod.hasActiveBody()) {
            LOG.info("Method run() in class {} does not have an active body, skipping", threadClass.getName());
            return;
        }

        LOG.info("Wrapping run() method in {}", threadClass.getName());

        Body body = runMethod.retrieveActiveBody();
        PatchingChain<Unit> units = body.getUnits();
        units.clear();
        body.getLocals().clear();
        body.getTraps().clear();

        runMethod.setModifiers(Modifier.PUBLIC);

        LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

        Local thisLocal = lg.generateLocal(threadClass.getType());
        Local pilotContextLocal = lg.generateLocal(RefType.v("io.opentelemetry.context.Context"));
        Local isDryRunLocal = lg.generateLocal(BooleanType.v());
        Local scopeLocal = lg.generateLocal(RefType.v("io.opentelemetry.context.Scope"));
        Local exceptionLocal = lg.generateLocal(RefType.v("java.lang.Throwable"));

        units.add(Jimple.v().newIdentityStmt(thisLocal,
                Jimple.v().newThisRef(threadClass.getType())));

        FieldRef contextFieldRef = Jimple.v().newInstanceFieldRef(
                thisLocal,
                threadClass.getField(getContextFieldName(threadClass),
                        RefType.v("io.opentelemetry.context.Context")).makeRef()
        );

        units.add(Jimple.v().newAssignStmt(pilotContextLocal, contextFieldRef));

        SootMethodRef isDryRunMethodRef = Scene.v().makeMethodRef(
                Scene.v().loadClassAndSupport("org.pilot.PilotUtil"),
                "isDryRun",
                Arrays.asList(RefType.v("io.opentelemetry.context.Context")),
                BooleanType.v(),
                true
        );

        StaticInvokeExpr isDryRunInvoke = Jimple.v().newStaticInvokeExpr(
                isDryRunMethodRef,
                pilotContextLocal
        );
        units.add(Jimple.v().newAssignStmt(isDryRunLocal, isDryRunInvoke));

        NopStmt dryRunBlock = Jimple.v().newNopStmt();
        IfStmt ifStmt = Jimple.v().newIfStmt(
                Jimple.v().newNeExpr(isDryRunLocal, IntConstant.v(0)),
                dryRunBlock
        );
        units.add(ifStmt);

        SootMethodRef runOriginalRef = Scene.v().makeMethodRef(
                threadClass,
                "run$original",
                Collections.emptyList(),
                VoidType.v(),
                false
        );
        InvokeStmt callOriginal = Jimple.v().newInvokeStmt(
                Jimple.v().newVirtualInvokeExpr(thisLocal, runOriginalRef)
        );
        units.add(callOriginal);
        units.add(Jimple.v().newReturnVoidStmt());

        units.add(dryRunBlock);

        SootMethodRef makeCurrentRef = Scene.v().makeMethodRef(
                Scene.v().loadClassAndSupport("io.opentelemetry.context.Context"),
                "makeCurrent",
                Collections.emptyList(),
                RefType.v("io.opentelemetry.context.Scope"),
                false
        );

        InterfaceInvokeExpr makeCurrentInvoke = Jimple.v().newInterfaceInvokeExpr(
                pilotContextLocal,
                makeCurrentRef
        );
        units.add(Jimple.v().newAssignStmt(scopeLocal, makeCurrentInvoke));

        NopStmt tryStart = Jimple.v().newNopStmt();
        units.add(tryStart);


        SootMethodRef runInstrumentationRef = Scene.v().makeMethodRef(
                threadClass,
                "run$instrumentation",
                Collections.emptyList(),
                VoidType.v(),
                false
        );
        units.add(Jimple.v().newInvokeStmt(
                Jimple.v().newVirtualInvokeExpr(thisLocal, runInstrumentationRef)
        ));

        NopStmt tryEnd = Jimple.v().newNopStmt();
        units.add(tryEnd);

        SootMethodRef closeRef = Scene.v().makeMethodRef(
                Scene.v().loadClassAndSupport("io.opentelemetry.context.Scope"),
                "close",
                Collections.emptyList(),
                VoidType.v(),
                false
        );
        units.add(Jimple.v().newInvokeStmt(
                Jimple.v().newInterfaceInvokeExpr(scopeLocal, closeRef)
        ));


        SootMethodRef deregisterZkRef = Scene.v().makeMethodRef(
                Scene.v().loadClassAndSupport("org.pilot.PilotUtil"),
                "deregisterFromZK",
                Collections.emptyList(),
                VoidType.v(),
                true  // static method
        );
        units.add(Jimple.v().newInvokeStmt(
                Jimple.v().newStaticInvokeExpr(deregisterZkRef)
        ));


        units.add(Jimple.v().newReturnVoidStmt());


        IdentityStmt catchStart = Jimple.v().newIdentityStmt(
                exceptionLocal,
                Jimple.v().newCaughtExceptionRef()
        );
        units.add(catchStart);


        units.add(Jimple.v().newInvokeStmt(
                Jimple.v().newInterfaceInvokeExpr(scopeLocal, closeRef)
        ));

        units.add(Jimple.v().newInvokeStmt(
                Jimple.v().newStaticInvokeExpr(deregisterZkRef)
        ));


        units.add(Jimple.v().newThrowStmt(exceptionLocal));


        body.getTraps().add(Jimple.v().newTrap(
                Scene.v().getSootClass("java.lang.Throwable"),
                tryStart,
                tryEnd,
                catchStart
        ));

        body.validate();
    }

    public ThreadTracer(SootClass sc, ClassFilterHelper filter) {
        this.sootClass = sc;
        this.filter = filter;
    }

}
