package edu.uva.liftlab.pilot.distributedtracing;

import edu.uva.liftlab.pilot.isolation.stateredirection.ClassFilterHelper;
import edu.uva.liftlab.pilot.transformer.PilotTransformer;
import edu.uva.liftlab.pilot.util.LocalGeneratorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;
import java.util.*;

public class SEDAWorkerPropagator {
    private static final Logger LOG = LoggerFactory.getLogger(SEDAWorkerPropagator.class);
    private SootClass sootClass;
    private ClassFilterHelper classFilterHelper;

    // Common SEDA/Worker patterns
    private static final Set<String> QUEUE_CLASSES = new HashSet<>(Arrays.asList(
            "java.util.concurrent.BlockingQueue",
            "java.util.concurrent.LinkedBlockingQueue",
            "java.util.concurrent.ArrayBlockingQueue",
            "java.util.concurrent.PriorityBlockingQueue",
            "java.util.concurrent.ConcurrentLinkedQueue",
            "java.util.Queue"
    ));

    public SEDAWorkerPropagator(SootClass sootClass, ClassFilterHelper classFilterHelper) {
        this.sootClass = sootClass;
        this.classFilterHelper = classFilterHelper;
    }

    public void propagateContext() {
        for (SootMethod method : sootClass.getMethods()) {
            if (!method.hasActiveBody()) continue;

            Body body = method.retrieveActiveBody();
            UnitPatchingChain units = body.getUnits();
            List<Unit> originalUnits = new ArrayList<>(units);
            LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

            for (Unit u : originalUnits) {
                InvokeExpr invoke = getInvokeExpr(u);
                if (invoke != null) {
                    if (isQueuePutOperation(invoke)) {
                        PilotTransformer.ctxCount++;
                        //wrapQueuePut(u, invoke, units, lg);
                    } else if (isQueueTakeOperation(invoke)) {
                        //wrapQueueTake(u, invoke, units, lg);
                    }
                }
            }
        }
    }

    private boolean isQueuePutOperation(InvokeExpr invoke) {
        SootMethod method = invoke.getMethod();
        String className = method.getDeclaringClass().getName();

        for (String queueClass : QUEUE_CLASSES) {
            if (isSubtypeOf(method.getDeclaringClass(), queueClass)) {
                String methodName = method.getName();
                return methodName.equals("put") || methodName.equals("offer") ||
                        methodName.equals("add") || methodName.equals("enqueue");
            }
        }
        return false;
    }

    private boolean isQueueTakeOperation(InvokeExpr invoke) {
        SootMethod method = invoke.getMethod();
        String className = method.getDeclaringClass().getName();

        for (String queueClass : QUEUE_CLASSES) {
            if (isSubtypeOf(method.getDeclaringClass(), queueClass)) {
                String methodName = method.getName();
                return methodName.equals("take") || methodName.equals("poll") ||
                        methodName.equals("remove") || methodName.equals("dequeue");
            }
        }
        return false;
    }

    private void wrapQueuePut(Unit unit, InvokeExpr invoke,
                              PatchingChain<Unit> units, LocalGeneratorUtil lg) {
        List<Unit> newUnits = new ArrayList<>();

        // Get current context
        SootClass contextClass = Scene.v().getSootClass("io.opentelemetry.context.Context");
        Local contextLocal = lg.generateLocal(RefType.v(contextClass));

        newUnits.add(Jimple.v().newAssignStmt(
                contextLocal,
                Jimple.v().newStaticInvokeExpr(
                        contextClass.getMethod("current", Collections.emptyList(),
                                RefType.v("io.opentelemetry.context.Context")).makeRef()
                )
        ));

        // Wrap the element being put into queue with context
        if (!invoke.getArgs().isEmpty()) {
            Value element = invoke.getArg(0);
            Local wrappedElement = wrapElementWithContext(element, contextLocal, lg, newUnits);

            // Replace argument with wrapped version
            List<Value> newArgs = new ArrayList<>(invoke.getArgs());
            newArgs.set(0, wrappedElement);

            // Create new invoke with wrapped element
            InvokeExpr newInvoke = createNewInvokeExpr(invoke, newArgs);
            setInvokeExpr(unit, newInvoke);
        }

        units.insertBefore(newUnits, unit);
    }

    private void wrapQueueTake(Unit unit, InvokeExpr invoke,
                               PatchingChain<Unit> units, LocalGeneratorUtil lg) {
        List<Unit> afterUnits = new ArrayList<>();

        // After taking from queue, extract and activate context
        if (unit instanceof AssignStmt) {
            AssignStmt stmt = (AssignStmt) unit;
            Local result = (Local) stmt.getLeftOp();

            // Extract context from wrapped element
            extractAndActivateContext(result, lg, afterUnits);
        }

        units.insertAfter(afterUnits, unit);
    }

    private Local wrapElementWithContext(Value element, Local context,
                                         LocalGeneratorUtil lg, List<Unit> units) {
        // Create wrapper that embeds context with the element
        // This would typically create a wrapper object containing both
        Local wrapped = lg.generateLocal(element.getType());
        units.add(Jimple.v().newAssignStmt(wrapped, element));
        return wrapped;
    }

    private void extractAndActivateContext(Local element, LocalGeneratorUtil lg,
                                           List<Unit> units) {
        // Extract context from wrapped element and activate it
    }

    private boolean isSubtypeOf(SootClass cls, String parentClassName) {
        if (cls.getName().equals(parentClassName)) return true;

        for (SootClass iface : cls.getInterfaces()) {
            if (iface.getName().equals(parentClassName)) return true;
        }

        if (cls.hasSuperclass()) {
            return isSubtypeOf(cls.getSuperclass(), parentClassName);
        }

        return false;
    }

    private InvokeExpr createNewInvokeExpr(InvokeExpr original, List<Value> newArgs) {
        return original;
    }

    private void setInvokeExpr(Unit unit, InvokeExpr newInvoke) {
        if (unit instanceof InvokeStmt) {
            ((InvokeStmt) unit).setInvokeExpr(newInvoke);
        } else if (unit instanceof AssignStmt) {
            ((AssignStmt) unit).setRightOp(newInvoke);
        }
    }

    private InvokeExpr getInvokeExpr(Unit unit) {
        if (unit instanceof InvokeStmt) {
            return ((InvokeStmt) unit).getInvokeExpr();
        } else if (unit instanceof AssignStmt) {
            Value rightOp = ((AssignStmt) unit).getRightOp();
            if (rightOp instanceof InvokeExpr) {
                return (InvokeExpr) rightOp;
            }
        }
        return null;
    }
}