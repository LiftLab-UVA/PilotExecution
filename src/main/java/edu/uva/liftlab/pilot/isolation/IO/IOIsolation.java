package edu.uva.liftlab.pilot.isolation.IO;

import edu.uva.liftlab.pilot.isolation.stateredirection.ClassFilterHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.Jimple;

public class IOIsolation {
    private static final Logger LOG = LoggerFactory.getLogger(IOIsolation.class);

    private final SootClass sootClass;
    private final IOIsolationProcessor ioProcessor;
    private SootMethod currentMethod;

    public IOIsolation(SootClass sootClass) {
        this.sootClass = sootClass;
        this.ioProcessor = new IOIsolationProcessor();
    }

    public static void redirectAllClassesIO(ClassFilterHelper filter) {

        for (SootClass sc : Scene.v().getApplicationClasses()) {
            if (filter.shouldSkip(sc)) {
                continue;
            }

            LOG.info("filter.ioClasses: {}", filter.ioClasses.size());
            if(filter.ioClasses.isEmpty()){
                LOG.info("Redirecting2 IO for class: {}", sc.getName());
                new IOIsolation(sc).redirectIO();
            }else if(filter.isContainsIoClass(sc)){
                LOG.info("Redirecting2 IO for class: {}", sc.getName());
                new IOIsolation(sc).redirectIO();
            }

        }
    }

    private void redirectIO() {
        for (SootMethod method : sootClass.getMethods()) {
            currentMethod = method;
            redirectMethodIO(method);
        }
    }


    private void redirectMethodIO(SootMethod method) {
        try {
            if (!method.hasActiveBody()) {
                return;
            }
            LOG.info("Redirecting IO for method: {}", method.getName());
            Body originalBody = method.retrieveActiveBody();
            Body instrumentationBody = Jimple.v().newBody(method);
            instrumentationBody.importBodyContentsFrom(originalBody);

            ioProcessor.redirectIOOperations(instrumentationBody);

            instrumentationBody.validate();
            method.setActiveBody(instrumentationBody);

            LOG.debug("Successfully redirected IO in method: {}", method.getName());
        } catch (Exception e) {
            LOG.error("Failed to redirect IO in method {}: {}", method.getName(), e.getMessage());
        }
    }


    private boolean shouldInstrumentMethod(SootMethod method) {
        return !(method.isAbstract() || method.isNative()) && method.hasActiveBody();
    }
}
