package edu.uva.liftlab.pilot.transformer;

import edu.uva.liftlab.pilot.analysis.PhaseInfo;
import edu.uva.liftlab.pilot.distributedtracing.BaggagePropagation;
import edu.uva.liftlab.pilot.distributedtracing.HTTPPropagator;
import edu.uva.liftlab.pilot.generator.PilotMethodGenerator;
import edu.uva.liftlab.pilot.generator.LockGenerator;
import edu.uva.liftlab.pilot.isolation.IO.IOIsolation;
import edu.uva.liftlab.pilot.isolation.stateredirection.ClassFilterHelper;
import edu.uva.liftlab.pilot.isolation.stateredirection.StateRedirection;
import edu.uva.liftlab.pilot.sanitization.Sanitization;
import edu.uva.liftlab.pilot.staticanalysis.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;

import java.util.*;

public class PilotTransformer extends SceneTransformer {

    public static final PhaseInfo PHASE_INFO = new PhaseInfo("wjtp", "transformer method",
            "Transform the run method of RpcExecutor$Handler", true, false);

    private static final Logger LOG = LoggerFactory.getLogger(PilotTransformer.class);

    public static final String SET_BY_DRY_RUN = "$setByDryRun";


    public String config_file_path;

    private PilotMethodGenerator pilotMethodGenerator;

    public LockGenerator lockGenerator = new LockGenerator();

    public HTTPPropagator httpPropagator;

    public ClassFilterHelper filter;

    public ExperimentCtxTransformer pilotTrackTransformer;

    public static boolean isInnerClass(SootClass sootClass) {
        return sootClass.getName().contains("$");
    }

    public static boolean isLambdaClass(SootClass sootClass) {
        return sootClass.getName().contains("$lambda");
    }

    private SedaQueueInstrumenter sedaQueueInstrumenter;

    public static int ctxCount=0;

    public static int transformationCount=0;

    public PilotTransformer(String config_file_path) {
        this.config_file_path = config_file_path;
        this.filter = new ClassFilterHelper(this.config_file_path);
        pilotMethodGenerator = new PilotMethodGenerator(filter);
        this.sedaQueueInstrumenter = new SedaQueueInstrumenter(filter);
        this.httpPropagator = new HTTPPropagator(filter);
        this.pilotTrackTransformer = new ExperimentCtxTransformer(filter);
    }



    public void instrument(){

        IOIsolation.redirectAllClassesIO(filter);
        this.httpPropagator.injectCtxHooks();
        this.pilotMethodGenerator.processClasses();
        StateRedirection.redirectAllClassesStates(filter);
        for(SootClass sc: Scene.v().getApplicationClasses()){
            // Skip interfaces and phantom classes
            boolean isPilotFuncBlackListClass = filter.isBlackListPilotFuncClass(sc) && !filter.isWhiteListPilotFuncClass(sc);
            if(isPilotFuncBlackListClass && !filter.isTraceClass(sc)){
                continue;
            }
            BaggagePropagation baggagePropagation = new BaggagePropagation(sc,filter);
            baggagePropagation.propagateBaggage();
        }
        this.pilotTrackTransformer.transform();
        //sedaQueueInstrumenter.instrumentSedaQueues();
        Sanitization.sanitizeAllClasses();
        //Large-scale micro fork with lockwrapper replacement sometimes makes the pilot execution unstable and buggy, temporarily excluded for AE experiments
    }


    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        LOG.info("filter.isSimpleInstrumentation: " + filter.isSimpleInstrumentation);
        if(filter.isSimpleInstrumentation){
            LOG.info("IO Classes: " + filter.ioClasses.toString());
            this.httpPropagator.injectCtxHooks();
            if(!filter.ioClasses.isEmpty()){
                IOIsolation.redirectAllClassesIO(filter);
            }
            return;
        }

        LOG.info("Complete instrumentation");
        instrument();
    }

}
