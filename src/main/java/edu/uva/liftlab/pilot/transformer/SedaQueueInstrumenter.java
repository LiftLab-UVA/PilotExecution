package edu.uva.liftlab.pilot.transformer;

import edu.uva.liftlab.pilot.isolation.stateredirection.ClassFilterHelper;
import edu.uva.liftlab.pilot.util.LocalGeneratorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.AssignStmt;
import soot.jimple.InstanceFieldRef;
import soot.jimple.Jimple;
import soot.jimple.StaticInvokeExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static edu.uva.liftlab.pilot.util.Constants.DRY_RUN_SUFFIX;

public class SedaQueueInstrumenter {
    private final ClassFilterHelper filter;
    private static final Logger LOG = LoggerFactory.getLogger(SedaQueueInstrumenter.class);

    public SedaQueueInstrumenter(ClassFilterHelper filter){
        this.filter = filter;
    }

    public void instrumentSedaQueues(){
        Map<String, String> sedaQueueMap = filter.sedaQueueMap;
        for(Map.Entry<String, String> entry: sedaQueueMap.entrySet()){
            String className = entry.getKey();
            String fieldName = entry.getValue();
            LOG.info("Attempting to instrument SEDA queue in class: {} for field: {}", className, fieldName);

            try{
                SootClass sootClass = Scene.v().getSootClass(className);
                if (sootClass == null || sootClass.isPhantom()) {
                    LOG.warn("Cannot find or load class: {}", className);
                    continue;
                }

                instrumentClassConstructors(sootClass, fieldName);
                LOG.info("Successfully instrumented SEDA queue in class: {} for field: {}", className, fieldName);
            } catch (Exception e) {
                LOG.error("Failed to instrument SEDA queue for class: {}", className, e);
            }

        }
    }

    private void instrumentClassConstructors(SootClass sootClass, String fieldName){
        SootField originalField = null;
        SootField dryRunField = null;
        try {
            originalField = sootClass.getFieldByName(fieldName);
            dryRunField = sootClass.getFieldByName(fieldName + DRY_RUN_SUFFIX);
        } catch(Exception e){
            LOG.error("Error retrieving fields for class: {} with field: {}", sootClass.getName(), fieldName, e);
            return;
        }

        if (originalField == null || dryRunField == null) {
            LOG.warn("Fields not found in class: {} for field: {}", sootClass.getName(), fieldName);
            return;
        }

        for(SootMethod method: sootClass.getMethods()){
            if(method.isConstructor() && method.hasActiveBody()){
                instrumentConstructor(method, originalField, dryRunField);
            }
        }

    }

    private void instrumentConstructor(SootMethod constructor, SootField originalField, SootField dryRunField){
        Body body = constructor.getActiveBody();
        PatchingChain<Unit> units = body.getUnits();
        LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

        List<Unit> fieldAssignments = new ArrayList<>();
        for(Unit unit : units){
            if (unit instanceof AssignStmt){
                AssignStmt assign = (AssignStmt) unit;
                if (assign.getLeftOp() instanceof InstanceFieldRef){
                    InstanceFieldRef fieldRef = (InstanceFieldRef) assign.getLeftOp();
                    if (fieldRef.getField().equals(originalField)) {
                        fieldAssignments.add(unit);
                        LOG.debug("Found assignment to field {} in constructor of {}",
                                originalField.getName(), constructor.getDeclaringClass().getName());
                    }
                }
            }
        }

        for(Unit assignmentUnit : fieldAssignments){
            Local thisLocal = body.getThisLocal();
            InstanceFieldRef originalFieldRef = Jimple.v().newInstanceFieldRef(thisLocal, originalField.makeRef());
            Local originalValueLocal = lg.generateLocal(originalField.getType());
            Unit loadOriginal = Jimple.v().newAssignStmt(originalValueLocal, originalFieldRef);
            SootClass stateClass = Scene.v().loadClassAndSupport("org.pilot.State");
            SootMethod deepCopyMethod = stateClass.getMethodByName("deepCopy");

            StaticInvokeExpr deepCopyCall = Jimple.v().newStaticInvokeExpr(
                    deepCopyMethod.makeRef(),
                    originalValueLocal
            );

            // Cast result if necessary
            Local deepCopyResult = lg.generateLocal(RefType.v("java.lang.Object"));
            Unit assignDeepCopy = Jimple.v().newAssignStmt(deepCopyResult, deepCopyCall);

            // Cast to the correct type
            Local castedResult = lg.generateLocal(originalField.getType());
            Unit castStmt = Jimple.v().newAssignStmt(
                    castedResult,
                    Jimple.v().newCastExpr(deepCopyResult, originalField.getType())
            );

            InstanceFieldRef dryRunFieldRef = Jimple.v().newInstanceFieldRef(
                    thisLocal, dryRunField.makeRef()
            );
            Unit assignToDryRun = Jimple.v().newAssignStmt(dryRunFieldRef, castedResult);

            // Insert all statements after the original assignment
            units.insertAfter(loadOriginal, assignmentUnit);
            units.insertAfter(assignDeepCopy, loadOriginal);
            units.insertAfter(castStmt, assignDeepCopy);
            units.insertAfter(assignToDryRun, castStmt);

            LOG.info("Added deep copy instrumentation for field {} after assignment in constructor of {}",
                    originalField.getName(), constructor.getDeclaringClass().getName());
        }
        body.validate();
    }
}
