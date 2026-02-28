package edu.uva.liftlab.pilot.distributedtracing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;

import java.util.Collections;

public class FutureCallbackParameterWrapper extends ParameterWrapper {
    private static final Logger LOG = LoggerFactory.getLogger(FutureCallbackParameterWrapper.class);

    public FutureCallbackParameterWrapper(SootClass sootClass) {
        super(sootClass);
    }


    public static boolean implementsFutureCallBack(SootClass cls) {
        if (cls.getName().equals("com.google.common.util.concurrent.FutureCallback")) {
            return true;
        }

        for (SootClass iface : cls.getInterfaces()) {
            if (implementsFutureCallBack(iface)) {
                return true;
            }
        }

        if (cls.hasSuperclass()) {
            return implementsFutureCallBack(cls.getSuperclass());
        }

        return false;
    }


    public void wrapParameter() {
        if (!implementsFutureCallBack(sootClass)) {
            return;
        }


        SootMethod onSuccessMethod;
        SootMethod onFailureMethod;
        try {
            onSuccessMethod = sootClass.getMethod("onSuccess", Collections.singletonList(RefType.v("java.lang.Object")), VoidType.v());
            onFailureMethod = sootClass.getMethod("onFailure", Collections.singletonList(RefType.v("java.lang.Throwable")), VoidType.v());
        } catch (RuntimeException e) {
            LOG.warn("No onSuccess or onFailure method found in class: {}", sootClass.getName());
            return;
        }
        wrapInterface(onSuccessMethod);
        wrapInterface(onFailureMethod);
    }
}
