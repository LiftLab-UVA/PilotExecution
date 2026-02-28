package edu.uva.liftlab.pilot.isolation.stateredirection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;

import static edu.uva.liftlab.pilot.transformer.PilotTransformer.SET_BY_DRY_RUN;
import static edu.uva.liftlab.pilot.util.Constants.DRY_RUN_SUFFIX;

public class FieldInfo {
    private static final Logger LOG = LoggerFactory.getLogger(FieldInfo.class);

    private final SootField originalField;
    private final SootField dryRunField;
    private final SootField isSetByDryRunField;
    private final boolean isValid;

    public FieldInfo(SootField originalField, SootField dryRunField, SootField isSetByDryRunField) {
        this.originalField = originalField;
        this.dryRunField = dryRunField;
        this.isSetByDryRunField = isSetByDryRunField;
        this.isValid = validateFields();
    }

    public FieldInfo(FieldRef fieldRef) {
        this.originalField = fieldRef.getField();
        this.dryRunField = findDryRunField(originalField);
        this.isSetByDryRunField = findSetByDryRunField(originalField);
        this.isValid = validateFields();
    }

    private SootField findDryRunField(SootField original) {
        try {
            return original.getDeclaringClass()
                    .getFieldByName(original.getName() + DRY_RUN_SUFFIX);
        } catch (RuntimeException e) {
            LOG.debug("Could not find dry run field for {}", original.getName());
            return null;
        }
    }

    private SootField findSetByDryRunField(SootField original) {
        try {
            return original.getDeclaringClass()
                    .getFieldByName(original.getName() + DRY_RUN_SUFFIX + SET_BY_DRY_RUN);
        } catch (RuntimeException e) {
            LOG.debug("Could not find set by dry run field for {}", original.getName());
            return null;
        }
    }

    private boolean validateFields() {
        if (dryRunField == null) {
            LOG.info("DryRun field is null for field {}", originalField.getName());
            return false;
        }
        if (isSetByDryRunField == null) {
            LOG.info("SetByDryRun field is null for field {}", originalField.getName());
            return false;
        }

        if (!dryRunField.getType().equals(originalField.getType())) {
            LOG.info("Type mismatch between original and dry run field for {}", originalField.getName());
            return false;
        }
        if (!isSetByDryRunField.getType().equals(IntType.v())) {
            LOG.info("SetByDryRun field is not boolean type for {}", originalField.getName());
            return false;
        }
        return true;
    }

    public SootField getOriginalField() {
        return originalField;
    }

    public SootField getDryRunField() {
        return dryRunField;
    }

    public SootField getIsSetByDryRunField() {
        return isSetByDryRunField;
    }

    public boolean isValid() {
        return isValid;
    }

    public boolean isStaticField() {
        return originalField.isStatic();
    }

    public Type getType() {
        return originalField.getType();
    }

    public String getOriginalFieldName() {
        return originalField.getName();
    }

    public String getDryRunFieldName() {
        return dryRunField.getName();
    }

    public String getSetByDryRunFieldName() {
        return isSetByDryRunField.getName();
    }

    public SootClass getDeclaringClass() {
        return originalField.getDeclaringClass();
    }

    public boolean isPrimitiveType() {
        return originalField.getType() instanceof PrimType;
    }

    public FieldRef createDryRunFieldRef(Value base) {
        if (isStaticField()) {
            return Jimple.v().newStaticFieldRef(dryRunField.makeRef());
        } else {
            return Jimple.v().newInstanceFieldRef(base, dryRunField.makeRef());
        }
    }

    public FieldRef createOriginalFieldRef(Value base) {
        if (isStaticField()) {
            return Jimple.v().newStaticFieldRef(originalField.makeRef());
        } else {
            return Jimple.v().newInstanceFieldRef(base, originalField.makeRef());
        }
    }

    public FieldRef createSetByDryRunFieldRef(Value base) {
        if (isStaticField()) {
            return Jimple.v().newStaticFieldRef(isSetByDryRunField.makeRef());
        } else {
            return Jimple.v().newInstanceFieldRef(base, isSetByDryRunField.makeRef());
        }
    }

    @Override
    public String toString() {
        return String.format("FieldInfo{original=%s, dryRun=%s, setByDryRun=%s, valid=%s}",
                originalField.getName(),
                dryRunField != null ? dryRunField.getName() : "null",
                isSetByDryRunField != null ? isSetByDryRunField.getName() : "null",
                isValid);
    }
}