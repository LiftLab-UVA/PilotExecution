package edu.uva.liftlab.pilot.isolation.stateredirection;

import edu.uva.liftlab.pilot.util.LocalGeneratorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static edu.uva.liftlab.pilot.util.Constants.STATE_ISOLATION_CLASS;
import static edu.uva.liftlab.pilot.util.SootUtils.printLog4j;
import static edu.uva.liftlab.pilot.util.SootUtils.printValue;

public class UnitGenerator {
    private static final Logger LOG = LoggerFactory.getLogger(UnitGenerator.class);

    public List<Unit> generateUnitsForWriteOperationToField(FieldRef fieldRef,
                                                            FieldInfo fieldInfo,
                                                            Local pilotIdLocal) {
        List<Unit> units = new ArrayList<>();

        Value base = fieldRef instanceof InstanceFieldRef ?
                ((InstanceFieldRef) fieldRef).getBase() : null;
        FieldRef setByDryRunRef = fieldInfo.createSetByDryRunFieldRef(base);

        units.add(Jimple.v().newAssignStmt(setByDryRunRef, pilotIdLocal));
        return units;
    }

    public List<Unit> generateUnits(FieldRef fieldRef, LocalGeneratorUtil lg,
                                    FieldInfo fieldInfo, SootMethod method,
                                    int id, Local pilotIdLocal) {
        List<Unit> units = new ArrayList<>();

        Local originalValueLocal = lg.generateLocalWithId(fieldInfo.getType(),
                "tmp_" + fieldInfo.getOriginalFieldName());
        Local dryRunValueLocal = lg.generateLocalWithId(fieldInfo.getType(),
                "tmp_" + fieldInfo.getDryRunFieldName());
        Local setByDryRunValueLocal = lg.generateLocalWithId(IntType.v(),
                "setByDryRunValue_" + fieldInfo.getOriginalFieldName());
        Local isSetLocal = lg.generateLocalWithId(IntType.v(),
                "isSet_" + fieldInfo.getOriginalFieldName());

        Value base = fieldRef instanceof InstanceFieldRef ?
                ((InstanceFieldRef) fieldRef).getBase() : null;
        FieldRef dryRunRef = fieldInfo.createDryRunFieldRef(base);
        FieldRef originalRef = fieldInfo.createOriginalFieldRef(base);
        FieldRef setByDryRunRef = fieldInfo.createSetByDryRunFieldRef(base);

        units.add(Jimple.v().newAssignStmt(originalValueLocal, originalRef));
        units.add(Jimple.v().newAssignStmt(dryRunValueLocal, dryRunRef));
        units.add(Jimple.v().newAssignStmt(setByDryRunValueLocal, setByDryRunRef));

        units.add(Jimple.v().newAssignStmt(isSetLocal, IntConstant.v(0)));

        Stmt continueLabel = Jimple.v().newNopStmt();

        units.add(Jimple.v().newIfStmt(
                Jimple.v().newGeExpr(setByDryRunValueLocal, pilotIdLocal),
                continueLabel));
        units.add(Jimple.v().newAssignStmt(isSetLocal, IntConstant.v(1)));
        units.add(continueLabel);

        List<Unit> logUnits = generateLogUnits(fieldInfo, method, lg, id);

        Stmt setByDryRunStmt = Jimple.v().newAssignStmt(setByDryRunRef, pilotIdLocal);

        if (fieldInfo.isPrimitiveType()) {
            generatePrimitiveTypeUnits(units, originalValueLocal, dryRunValueLocal,
                    isSetLocal, dryRunRef, logUnits, setByDryRunStmt, lg,
                    fieldInfo, id);
        } else {
            generateReferenceTypeUnits(units, originalValueLocal, dryRunValueLocal,
                    isSetLocal, dryRunRef, logUnits, setByDryRunStmt, lg,
                    fieldInfo);
        }

        return units;
    }

    private List<Unit> generateLogUnits(FieldInfo fieldInfo, SootMethod method,
                                        LocalGeneratorUtil lg, int id) {
        String message = String.format("%s with id %d in function %s in class %s is set to be pilotID",
                fieldInfo.getSetByDryRunFieldName(),
                id,
                method.getName(),
                fieldInfo.getDeclaringClass().getName());
        return printLog4j(message, lg);
    }

    private void generatePrimitiveTypeUnits(List<Unit> units, Local originalValueLocal,
                                            Local dryRunValueLocal, Local isSetLocal,
                                            FieldRef dryRunRef, List<Unit> logUnits,
                                            Stmt setByDryRunStmt, LocalGeneratorUtil lg,
                                            FieldInfo fieldInfo, int id) {
        Stmt nopStmt = Jimple.v().newNopStmt();
        Stmt copyStmt = Jimple.v().newAssignStmt(dryRunRef, originalValueLocal);

        units.add(Jimple.v().newIfStmt(
                Jimple.v().newEqExpr(isSetLocal, IntConstant.v(0)),
                nopStmt));
        units.add(copyStmt);
        units.add(setByDryRunStmt);
        units.addAll(logUnits);
        units.add(nopStmt);

//        List<Unit> valueLogUnits = generateValueLogUnits(originalValueLocal, dryRunValueLocal,
//                lg, fieldInfo, id);
//        units.addAll(valueLogUnits);
    }

    private void generateReferenceTypeUnits(List<Unit> units, Local originalValueLocal,
                                            Local dryRunValueLocal, Local isSetLocal,
                                            FieldRef dryRunRef, List<Unit> logUnits,
                                            Stmt setByDryRunStmt, LocalGeneratorUtil lg,
                                            FieldInfo fieldInfo) {
        RefType objectType = RefType.v("java.lang.Object");

        Local booleanIsSetLocal = lg.generateLocal(BooleanType.v());

        units.add(Jimple.v().newAssignStmt(booleanIsSetLocal, isSetLocal));

        SootMethodRef shallowCopyMethod = Scene.v().makeMethodRef(
                Scene.v().getSootClass(STATE_ISOLATION_CLASS),
                "shallowCopy",
                Arrays.asList(objectType, objectType, BooleanType.v()),
                objectType,
                true);

        Local resultLocal = lg.generateLocal(fieldInfo.getType());
        Local castOriginal = lg.generateLocal(objectType);
        Local castDryRun = lg.generateLocal(objectType);

        units.add(Jimple.v().newAssignStmt(
                castOriginal,
                Jimple.v().newCastExpr(originalValueLocal, objectType)));
        units.add(Jimple.v().newAssignStmt(
                castDryRun,
                Jimple.v().newCastExpr(dryRunValueLocal, objectType)));


        Local tmpResult = lg.generateLocal(objectType);
        units.add(Jimple.v().newAssignStmt(
                tmpResult,
                Jimple.v().newStaticInvokeExpr(shallowCopyMethod,
                        castOriginal,
                        castDryRun,
                        booleanIsSetLocal)));

        units.add(Jimple.v().newAssignStmt(
                resultLocal,
                Jimple.v().newCastExpr(tmpResult, fieldInfo.getType())));

        units.add(Jimple.v().newAssignStmt(dryRunRef, resultLocal));
        units.add(setByDryRunStmt);
        units.addAll(logUnits);
    }

    private List<Unit> generateValueLogUnits(Local originalValueLocal, Local dryRunValueLocal,
                                             LocalGeneratorUtil lg, FieldInfo fieldInfo, int id) {
        List<Unit> units = new ArrayList<>();

        String originalMessage = String.format(" with id %d originalField is %s in class %s",
                id,
                fieldInfo.getOriginalFieldName(),
                fieldInfo.getDeclaringClass().getName());
        units.addAll(printValue(originalValueLocal, lg, originalMessage));

        String dryRunMessage = String.format(" with id %d dryRunField is %s in class %s",
                id,
                fieldInfo.getDryRunFieldName(),
                fieldInfo.getDeclaringClass().getName());
        units.addAll(printValue(dryRunValueLocal, lg, dryRunMessage));

        return units;
    }
}