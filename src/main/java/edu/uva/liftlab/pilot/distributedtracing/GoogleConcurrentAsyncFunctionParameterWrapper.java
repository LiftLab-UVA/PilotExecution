package edu.uva.liftlab.pilot.distributedtracing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;

import java.util.Collections;
import java.util.List;

public class GoogleConcurrentAsyncFunctionParameterWrapper extends ParameterWrapper{
    private static final Logger LOG = LoggerFactory.getLogger(GoogleConcurrentAsyncFunctionParameterWrapper.class);
    public GoogleConcurrentAsyncFunctionParameterWrapper(SootClass sootClass){
        super(sootClass);
    }

    public static boolean implementsAsyncFunction(SootClass cls) {
        if (cls.getName().equals("com.google.common.util.concurrent.AsyncFunction")) {
            return true;
        }

        for (SootClass iface : cls.getInterfaces()) {
            if (implementsAsyncFunction(iface)) {
                return true;
            }
        }

        if (cls.hasSuperclass()) {
            return implementsAsyncFunction(cls.getSuperclass());
        }

        return false;
    }

    public void wrapParameter() {
        if (!implementsAsyncFunction(sootClass)) {
            return;
        }

        SootMethod applyMethod;
        try {
            List<Type> parameterTypes = Collections.singletonList(RefType.v("java.lang.Object"));
            Type returnType = RefType.v("com.google.common.util.concurrent.ListenableFuture");
            applyMethod = sootClass.getMethod("apply", parameterTypes, returnType);
        } catch (RuntimeException e) {
            LOG.warn("No apply method found in class: {}", sootClass.getName());
            return;
        }

        wrapInterface(applyMethod);
    }
}
