package edu.uva.liftlab.pilot.distributedtracing;

import edu.uva.liftlab.pilot.util.LocalGeneratorUtil;
import soot.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.jimple.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static edu.uva.liftlab.pilot.util.Constants.INSTRUMENTATION_SUFFIX;

public class RunnableParameterWrapper extends ParameterWrapper {

    private static final Logger LOG = LoggerFactory.getLogger(RunnableParameterWrapper.class);

    public RunnableParameterWrapper(SootClass sootClass) {
        super(sootClass);
    }

    @Override
    public void wrapParameter() {
        for (SootMethod method : sootClass.getMethods()) {
            if (!method.isConcrete()) continue;
            if (!method.getName().endsWith(INSTRUMENTATION_SUFFIX)) {
                continue;
            }
            try {
                wrapRunnableParametersInMethod(method);
            } catch (Exception e) {
                LOG.warn("Failed to wrap runnable parameters in method {}: {}",
                        method.getSignature(), e.getMessage());
            }
        }
    }


    public void wrapRunnableParametersInMethod(SootMethod method) {
        if (!method.isConcrete()) {
            return;
        }

        Body body = method.retrieveActiveBody();
        PatchingChain<Unit> units = body.getUnits();
        LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

        List<Unit> unitsToProcess = new ArrayList<>();
        for (Unit unit : units) {
            unitsToProcess.add(unit);
        }

        for (Unit unit : unitsToProcess) {
            if (unit instanceof AssignStmt) {
                AssignStmt assignStmt = (AssignStmt) unit;
                Value rightOp = assignStmt.getRightOp();

                if (rightOp instanceof NewExpr) {
                    NewExpr newExpr = (NewExpr) rightOp;
                    String className = newExpr.getType().toString();

                    
                    if (isThreadOrDaemonClass(className)) {
                        
                        Unit constructorCall = findConstructorCall(units, unit, assignStmt.getLeftOp());
                        if (constructorCall != null && constructorCall instanceof InvokeStmt) {
                            LOG.info("Found constructor call for {}: {}",
                                    className, constructorCall);
                            InvokeStmt invokeStmt = (InvokeStmt) constructorCall;
                            InvokeExpr invokeExpr = invokeStmt.getInvokeExpr();

                            if (invokeExpr instanceof SpecialInvokeExpr) {
                                SpecialInvokeExpr specialInvoke = (SpecialInvokeExpr) invokeExpr;

                                if (hasRunnableParameter(specialInvoke)) {
                                    wrapRunnableArgument(body, units, lg, specialInvoke, constructorCall);
                                }
                            }
                        }
                    }
                }
            }
        }

        body.validate();
    }


    private boolean isThreadOrDaemonClass(String className) {
        return className.equals("java.lang.Thread");
    }

    private Unit findConstructorCall(PatchingChain<Unit> units, Unit newExprUnit, Value targetLocal) {
        Iterator<Unit> iterator = units.iterator(newExprUnit);
        iterator.next();

        int searchLimit = 30;
        int count = 0;

        while (iterator.hasNext() && count < searchLimit) {
            Unit nextUnit = iterator.next();
            count++;

            if (nextUnit instanceof InvokeStmt) {
                InvokeStmt invokeStmt = (InvokeStmt) nextUnit;
                InvokeExpr invokeExpr = invokeStmt.getInvokeExpr();

                if (invokeExpr instanceof SpecialInvokeExpr) {
                    SpecialInvokeExpr specialInvoke = (SpecialInvokeExpr) invokeExpr;

                    if (specialInvoke.getBase().equals(targetLocal) &&
                            specialInvoke.getMethod().getName().equals("<init>")) {
                        return nextUnit;
                    }
                }
            }
        }

        return null;
    }

    private boolean hasRunnableParameter(SpecialInvokeExpr specialInvoke) {
        SootMethod constructor = specialInvoke.getMethod();
        List<Type> paramTypes = constructor.getParameterTypes();

        for (Type type : paramTypes) {
            if (type.toString().equals("java.lang.Runnable")) {
                return true;
            }
        }

        return false;
    }

    private void wrapRunnableArgument(Body body, PatchingChain<Unit> units, LocalGeneratorUtil lg,
                                      SpecialInvokeExpr specialInvoke, Unit constructorCall) {

        List<Value> args = specialInvoke.getArgs();
        List<Type> paramTypes = specialInvoke.getMethod().getParameterTypes();

        for (int i = 0; i < paramTypes.size(); i++) {
            if (paramTypes.get(i).toString().equals("java.lang.Runnable")) {
                Value originalRunnable = args.get(i);

                List<Unit> newUnits = new ArrayList<>();
                Local wrappedRunnable = wrapRunnableWithContext(lg, originalRunnable, newUnits);


                units.insertBefore(newUnits, constructorCall);

                List<Value> newArgs = new ArrayList<>(args);
                newArgs.set(i, wrappedRunnable);

                SpecialInvokeExpr newSpecialInvoke = Jimple.v().newSpecialInvokeExpr(
                        (Local) specialInvoke.getBase(),
                        specialInvoke.getMethodRef(),
                        newArgs
                );

                InvokeStmt newInvokeStmt = Jimple.v().newInvokeStmt(newSpecialInvoke);

                units.insertAfter(newInvokeStmt, constructorCall);
                units.remove(constructorCall);

                LOG.debug("Wrapped Runnable parameter in constructor: {}",
                        specialInvoke.getMethod().getSignature());

                break;
            }
        }
    }


    private Local wrapRunnableWithContext(LocalGeneratorUtil lg, Value originalRunnable, List<Unit> newUnits) {

        SootClass contextClass = Scene.v().getSootClass("io.opentelemetry.context.Context");

        Local tempRunnable = lg.generateLocal(originalRunnable.getType());
        newUnits.add(Jimple.v().newAssignStmt(tempRunnable, originalRunnable));

        Local contextLocal = lg.generateLocal(RefType.v(contextClass));


        newUnits.add(Jimple.v().newAssignStmt(
                contextLocal,
                Jimple.v().newStaticInvokeExpr(
                        contextClass.getMethod("current",
                                Collections.emptyList(),
                                RefType.v("io.opentelemetry.context.Context")
                        ).makeRef()
                )
        ));

        newUnits.add(Jimple.v().newAssignStmt(
                tempRunnable,
                Jimple.v().newInterfaceInvokeExpr(
                        contextLocal,
                        contextClass.getMethod("wrap",
                                Collections.singletonList(RefType.v("java.lang.Runnable")),
                                RefType.v("java.lang.Runnable")
                        ).makeRef(),
                        tempRunnable
                )
        ));

        return tempRunnable;
    }
}