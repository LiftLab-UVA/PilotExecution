package edu.uva.liftlab.pilot.isolation.stateredirection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;

import static edu.uva.liftlab.pilot.util.Constants.DRY_RUN_SUFFIX;
import static edu.uva.liftlab.pilot.transformer.PilotTransformer.SET_BY_DRY_RUN;

public class FieldRedirector {
    private static final Logger LOG = LoggerFactory.getLogger(FieldRedirector.class);

    public FieldRef getDryRunFieldRef(FieldRef originalFieldRef) {
        try {
            SootField originalField = originalFieldRef.getField();
            SootField dryRunField = getDryRunField(originalField);

            if (originalField.getName().contains("SchedulerEventType")) {
                LOG.info("SchedulerEventType field found: {} with dry run field: {}",
                        originalField,
                        dryRunField);
            }

            if (dryRunField == null) {
                return null;
            }
            return createFieldRef(originalFieldRef, dryRunField);
        } catch (RuntimeException e) {
            LOG.info("Field {} does not have a dry run field in class {}",
                    originalFieldRef.getField(),
                    originalFieldRef.getField().getDeclaringClass());
            return null;
        }
    }


    public FieldRef getSetByDryRunFieldRef(FieldRef originalFieldRef) {
        try {
            SootField originalField = originalFieldRef.getField();
            SootField setByDryRunField = getSetByDryRunField(originalField);
            if (setByDryRunField == null) {
                return null;
            }
            return createFieldRef(originalFieldRef, setByDryRunField);
        } catch (RuntimeException e) {
            LOG.info("Field {} does not have a set by dry run field in class {}",
                    originalFieldRef.getField(),
                    originalFieldRef.getField().getDeclaringClass());
            return null;
        }
    }


    public SootField getDryRunField(SootField originalField) {
        try {
            String dryRunFieldName = originalField.getName() + DRY_RUN_SUFFIX;
            return originalField.getDeclaringClass().getFieldByName(dryRunFieldName);
        } catch (RuntimeException e) {
            LOG.debug("Could not find dry run field for {}", originalField.getName());
            return null;
        }
    }

    public SootField getSetByDryRunField(SootField originalField) {
        try {
            String setByDryRunFieldName = originalField.getName() + DRY_RUN_SUFFIX + SET_BY_DRY_RUN;
            return originalField.getDeclaringClass().getFieldByName(setByDryRunFieldName);
        } catch (RuntimeException e) {
            LOG.debug("Could not find set by dry run field for {}", originalField.getName());
            return null;
        }
    }


    private FieldRef createFieldRef(FieldRef originalFieldRef, SootField newField) {
        if (originalFieldRef instanceof StaticFieldRef) {
            return Jimple.v().newStaticFieldRef(newField.makeRef());
        } else {
            InstanceFieldRef instanceFieldRef = (InstanceFieldRef) originalFieldRef;
            return Jimple.v().newInstanceFieldRef(instanceFieldRef.getBase(),
                    newField.makeRef());
        }
    }

    public boolean shouldProcessField(SootField field) {
        if (field.getName().contains("assertionsDisabled")) {
            return false;
        }
        return !field.getName().endsWith(DRY_RUN_SUFFIX) &&
                !field.getName().endsWith(SET_BY_DRY_RUN);
    }


    public boolean isValidDryRunField(SootField field) {
        return field.getName().endsWith(DRY_RUN_SUFFIX) ||
                field.getName().endsWith(SET_BY_DRY_RUN);
    }


    public Value getDefaultValue(Type type) {
        if (type instanceof RefType) {
            return NullConstant.v();
        } else if (type instanceof IntType || type instanceof ByteType
                || type instanceof ShortType || type instanceof CharType) {
            return IntConstant.v(0);
        } else if (type instanceof LongType) {
            return LongConstant.v(0L);
        } else if (type instanceof FloatType) {
            return FloatConstant.v(0.0f);
        } else if (type instanceof DoubleType) {
            return DoubleConstant.v(0.0d);
        } else if (type instanceof BooleanType) {
            return IntConstant.v(0);
        }
        return NullConstant.v();
    }


    public boolean isDryRunPair(SootField original, SootField dryRun) {
        return dryRun.getName().equals(original.getName() + DRY_RUN_SUFFIX);
    }


    public String getOriginalFieldName(String dryRunFieldName) {
        if (dryRunFieldName.endsWith(DRY_RUN_SUFFIX)) {
            return dryRunFieldName.substring(0,
                    dryRunFieldName.length() - DRY_RUN_SUFFIX.length());
        }
        return dryRunFieldName;
    }

    private void logError(String message, Exception e) {
        LOG.error("Error in FieldRedirector: {} - {}", message, e.getMessage());
        if (LOG.isDebugEnabled()) {
            LOG.debug("Stack trace:", e);
        }
    }
}