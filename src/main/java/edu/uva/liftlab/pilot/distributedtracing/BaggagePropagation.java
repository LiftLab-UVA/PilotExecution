package edu.uva.liftlab.pilot.distributedtracing;

import edu.uva.liftlab.pilot.isolation.stateredirection.ClassFilterHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.SootClass;

public class BaggagePropagation {
    SootClass sootClass;

    RunnableParameterWrapper runnableParameterWrapper;

    FutureCallbackParameterWrapper futureCallbackParameterWrapper;

    GoogleConcurrentAsyncFunctionParameterWrapper googleConcurrentAsyncFunctionParameterWrapper;

    CallablePropagator callablePropagator;

    ExecutorPropagator executorPropagator;

    FuturePropagator futurePropagator;

    ClassFilterHelper classFilterHelper;

    ConditionVariablePropagator conditionVariablePropagator;
    SEDAWorkerPropagator sedaWorkerPropagator;
    SharedStatePropagator sharedStatePropagator;
    ThreadTracer threadTracer;
    private static final Logger LOG = LoggerFactory.getLogger(BaggagePropagation.class);


    public BaggagePropagation(SootClass sootClass, ClassFilterHelper filterHelper){
        this.sootClass = sootClass;
        this.classFilterHelper = filterHelper;
        this.runnableParameterWrapper = new RunnableParameterWrapper(sootClass);
        this.futureCallbackParameterWrapper = new FutureCallbackParameterWrapper(sootClass);
        this.googleConcurrentAsyncFunctionParameterWrapper = new GoogleConcurrentAsyncFunctionParameterWrapper(sootClass);
        this.callablePropagator = new CallablePropagator(sootClass);
        this.executorPropagator = new ExecutorPropagator(sootClass, classFilterHelper);
        this.futurePropagator = new FuturePropagator(sootClass, classFilterHelper);
        this.conditionVariablePropagator = new ConditionVariablePropagator(sootClass, classFilterHelper);
        this.sedaWorkerPropagator = new SEDAWorkerPropagator(sootClass, classFilterHelper);
        this.sharedStatePropagator = new SharedStatePropagator(sootClass, classFilterHelper);
        this.threadTracer = new ThreadTracer(this.sootClass, classFilterHelper);
    }

    public void propagateContextExperiment(){
        this.futurePropagator.propagateContextExperiment();
        this.executorPropagator.propagateContextExperiment();
//        this.futurePropagator.contextTracking();
//        this.executorPropagator.contextTracking();
    }


    public void propagateBaggage() {

        //this.runnableParameterWrapper.wrapParameter();
        this.threadTracer.instrument();
//        this.futureCallbackParameterWrapper.wrapParameter();
//        this.googleConcurrentAsyncFunctionParameterWrapper.wrapParameter();
//        this.sedaWorkerPropagator.propagateContext();
        this.sharedStatePropagator.propagateContext();
        this.futurePropagator.propagateContext();
        this.executorPropagator.propagateContext();
        LOG.info("Finished propagating baggage for class: {}", sootClass.getName());
    }
}
