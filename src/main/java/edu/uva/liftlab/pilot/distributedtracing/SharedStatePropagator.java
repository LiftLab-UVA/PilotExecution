package edu.uva.liftlab.pilot.distributedtracing;

import edu.uva.liftlab.pilot.isolation.stateredirection.ClassFilterHelper;
import edu.uva.liftlab.pilot.transformer.PilotTransformer;
import edu.uva.liftlab.pilot.util.LocalGeneratorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;
import java.util.*;

import static edu.uva.liftlab.pilot.util.Constants.INSTRUMENTATION_SUFFIX;

public class SharedStatePropagator {
    private static final Logger LOG = LoggerFactory.getLogger(SharedStatePropagator.class);
    private SootClass sootClass;
    private ClassFilterHelper classFilterHelper;

    private static final String PROPAGATE_CTX_FIELD = "propagateCtx";
    private static final String PILOT_UTIL_CLASS = "org.pilot.PilotUtil";
    private static final String CONTEXT_CLASS = "io.opentelemetry.context.Context";

    public SharedStatePropagator(SootClass sootClass, ClassFilterHelper classFilterHelper) {
        this.sootClass = sootClass;
        this.classFilterHelper = classFilterHelper;
    }

    public void propagateContext() {
        Set<SootField> loopConditionFields = identifyLoopConditionFields();

        if (loopConditionFields.isEmpty()) {
            return;
        }

        addPropagateCtxField();

        for (SootMethod method : sootClass.getMethods()) {
            if (!method.hasActiveBody()) continue;

            if (method.getName().endsWith(INSTRUMENTATION_SUFFIX)) {
                instrumentFieldWriteInPilotMethod(method, loopConditionFields);
            } else {
                instrumentFieldReadInOriginalMethod(method, loopConditionFields);
            }
        }
    }

    private Set<SootField> identifyLoopConditionFields() {
        Set<SootField> loopConditionFields = new HashSet<>();

        for (SootMethod method : sootClass.getMethods()) {
            if (!method.hasActiveBody()) continue;

            Body body = method.retrieveActiveBody();

            for (Unit unit : body.getUnits()) {
                if (unit instanceof IfStmt) {
                    IfStmt ifStmt = (IfStmt) unit;
                    Value condition = ifStmt.getCondition();

                    if (isPartOfWhileLoop(ifStmt, body)) {
                        Set<SootField> fields = extractFieldsFromCondition(condition, body);
                        loopConditionFields.addAll(fields);
                    }
                }
            }
        }

        for (SootField field : loopConditionFields) {
            PilotTransformer.ctxCount++;
            LOG.info("Found loop condition field: " + field.getName() + " in class: " + sootClass.getName());
        }

        return loopConditionFields;
    }

    private boolean isPartOfWhileLoop(IfStmt ifStmt, Body body) {
        Unit target = ifStmt.getTarget();
        List<Unit> units = new ArrayList<>(body.getUnits());
        int ifIndex = units.indexOf(ifStmt);
        int targetIndex = units.indexOf(target);

        if (targetIndex < ifIndex) {
            return true;
        }

        for (Unit unit : body.getUnits()) {
            if (unit instanceof GotoStmt) {
                GotoStmt gotoStmt = (GotoStmt) unit;
                Unit gotoTarget = gotoStmt.getTarget();
                int gotoIndex = units.indexOf(unit);
                int gotoTargetIndex = units.indexOf(gotoTarget);

                if (gotoTargetIndex <= ifIndex && gotoIndex > ifIndex && gotoIndex < targetIndex) {
                    return true;
                }
            }
        }

        return false;
    }

    private Set<SootField> extractFieldsFromCondition(Value condition, Body body) {
        Set<SootField> fields = new HashSet<>();

        if (condition instanceof BinopExpr) {
            BinopExpr binop = (BinopExpr) condition;
            extractFieldsFromValue(binop.getOp1(), fields, body);
            extractFieldsFromValue(binop.getOp2(), fields, body);
        } else {
            extractFieldsFromValue(condition, fields, body);
        }

        return fields;
    }

    private void extractFieldsFromValue(Value value, Set<SootField> fields, Body body) {
        if (value instanceof FieldRef) {
            FieldRef fieldRef = (FieldRef) value;
            SootField field = fieldRef.getField();
            if (field.getDeclaringClass().equals(sootClass)) {
                fields.add(field);
            }
        } else if (value instanceof Local) {
            Local local = (Local) value;
            for (Unit unit : body.getUnits()) {
                if (unit instanceof AssignStmt) {
                    AssignStmt assign = (AssignStmt) unit;
                    if (assign.getLeftOp().equals(local) && assign.getRightOp() instanceof FieldRef) {
                        FieldRef fieldRef = (FieldRef) assign.getRightOp();
                        SootField field = fieldRef.getField();
                        if (field.getDeclaringClass().equals(sootClass)) {
                            fields.add(field);
                        }
                    }
                }
            }
        }
    }

    private void addPropagateCtxField() {
        if (sootClass.declaresFieldByName(PROPAGATE_CTX_FIELD)) {
            return;
        }

        SootField ctxField = new SootField(
                PROPAGATE_CTX_FIELD,
                RefType.v(CONTEXT_CLASS),
                Modifier.PUBLIC | Modifier.VOLATILE
        );

        sootClass.addField(ctxField);
        LOG.info("Added propagateCtx field to class: " + sootClass.getName());
    }

    private void instrumentFieldWriteInPilotMethod(SootMethod method, Set<SootField> targetFields) {
        Body body = method.retrieveActiveBody();
        UnitPatchingChain units = body.getUnits();
        List<Unit> originalUnits = new ArrayList<>(units);
        LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

        for (Unit unit : originalUnits) {
            if (unit instanceof AssignStmt) {
                AssignStmt assignStmt = (AssignStmt) unit;
                Value leftOp = assignStmt.getLeftOp();

                if (leftOp instanceof InstanceFieldRef) {
                    InstanceFieldRef fieldRef = (InstanceFieldRef) leftOp;
                    SootField field = fieldRef.getField();

                    if (targetFields.contains(field)) {
                        List<Unit> instrumentation = createWriteInstrumentation(fieldRef, lg, body);
                        units.insertAfter(instrumentation, unit);
                    }
                }
            }
        }

        body.validate();
    }

    private List<Unit> createWriteInstrumentation(InstanceFieldRef fieldRef, LocalGeneratorUtil lg, Body body) {
        List<Unit> code = new ArrayList<>();

        Local currentCtx = lg.generateLocal(RefType.v(CONTEXT_CLASS));

        SootMethod currentMethod = Scene.v().getSootClass(CONTEXT_CLASS)
                .getMethod("current", Collections.emptyList(), RefType.v(CONTEXT_CLASS));

        code.add(Jimple.v().newAssignStmt(
                currentCtx,
                Jimple.v().newStaticInvokeExpr(currentMethod.makeRef())
        ));

        SootField propagateCtxField = sootClass.getFieldByName(PROPAGATE_CTX_FIELD);

        code.add(Jimple.v().newAssignStmt(
                Jimple.v().newInstanceFieldRef(fieldRef.getBase(), propagateCtxField.makeRef()),
                currentCtx
        ));

        return code;
    }

    private void instrumentFieldReadInOriginalMethod(SootMethod method, Set<SootField> targetFields) {
        Body body = method.retrieveActiveBody();
        UnitPatchingChain units = body.getUnits();
        List<Unit> originalUnits = new ArrayList<>(units);
        LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

        Set<Unit> instrumentedUnits = new HashSet<>();

        for (Unit unit : originalUnits) {
            if (instrumentedUnits.contains(unit)) {
                continue;
            }

            for (ValueBox useBox : unit.getUseBoxes()) {
                Value value = useBox.getValue();

                if (value instanceof InstanceFieldRef) {
                    InstanceFieldRef fieldRef = (InstanceFieldRef) value;
                    SootField field = fieldRef.getField();

                    if (targetFields.contains(field)) {
                        List<Unit> instrumentation = createReadInstrumentation(fieldRef, lg, body);
                        units.insertBefore(instrumentation, unit);
                        instrumentedUnits.add(unit);
                        break;
                    }
                }
            }
        }

        body.validate();
    }

    private List<Unit> createReadInstrumentation(InstanceFieldRef fieldRef, LocalGeneratorUtil lg, Body body) {
        List<Unit> code = new ArrayList<>();

        SootField propagateCtxField = sootClass.getFieldByName(PROPAGATE_CTX_FIELD);

        Local propagateCtx = lg.generateLocal(RefType.v(CONTEXT_CLASS));
        code.add(Jimple.v().newAssignStmt(
                propagateCtx,
                Jimple.v().newInstanceFieldRef(fieldRef.getBase(), propagateCtxField.makeRef())
        ));

        NopStmt skipLabel = Jimple.v().newNopStmt();

        code.add(Jimple.v().newIfStmt(
                Jimple.v().newEqExpr(propagateCtx, NullConstant.v()),
                skipLabel
        ));

        Local ctxPilotId = lg.generateLocal(IntType.v());
        code.add(Jimple.v().newAssignStmt(
                ctxPilotId,
                Jimple.v().newStaticInvokeExpr(
                        Scene.v().makeMethodRef(
                                Scene.v().getSootClass(PILOT_UTIL_CLASS),
                                "getPilotID",
                                Arrays.asList(RefType.v(CONTEXT_CLASS)),
                                IntType.v(),
                                true
                        ),
                        propagateCtx
                )
        ));

        code.add(Jimple.v().newIfStmt(
                Jimple.v().newEqExpr(ctxPilotId, IntConstant.v(0)),
                skipLabel
        ));

        Local currentPilotId = lg.generateLocal(IntType.v());
        code.add(Jimple.v().newAssignStmt(
                currentPilotId,
                Jimple.v().newStaticInvokeExpr(
                        Scene.v().makeMethodRef(
                                Scene.v().getSootClass(PILOT_UTIL_CLASS),
                                "getCurrentPilotID",
                                Collections.emptyList(),
                                IntType.v(),
                                true
                        )
                )
        ));

        code.add(Jimple.v().newIfStmt(
                Jimple.v().newNeExpr(ctxPilotId, currentPilotId),
                skipLabel
        ));

        Local currentThread = lg.generateLocal(RefType.v("java.lang.Thread"));
        code.add(Jimple.v().newAssignStmt(
                currentThread,
                Jimple.v().newStaticInvokeExpr(
                        Scene.v().makeMethodRef(
                                Scene.v().getSootClass("java.lang.Thread"),
                                "currentThread",
                                Collections.emptyList(),
                                RefType.v("java.lang.Thread"),
                                true
                        )
                )
        ));

        code.add(Jimple.v().newInvokeStmt(
                Jimple.v().newStaticInvokeExpr(
                        Scene.v().makeMethodRef(
                                Scene.v().getSootClass(PILOT_UTIL_CLASS),
                                "microFork",
                                Arrays.asList(RefType.v("java.lang.Thread"), RefType.v(CONTEXT_CLASS)),
                                VoidType.v(),
                                true
                        ),
                        currentThread,
                        propagateCtx
                )
        ));

        code.add(skipLabel);

        return code;
    }
}