package edu.uva.liftlab.pilot.isolation.stateredirection;

import edu.uva.liftlab.pilot.util.PropertyType;
import edu.uva.liftlab.pilot.util.SootUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.SootClass;

import java.util.*;

public class ClassFilterHelper {
    private static final Logger LOG = LoggerFactory.getLogger(ClassFilterHelper.class);

    private final String configPath;
    public final Set<String> blackPilotFuncList;
    public final Set<String> whitePilotFuncList;
    public final Set<String> ctxTreeBlackList;
    public final Set<String> stateBlackList;
    public final Set<String> stateWhiteList;
    public final Set<String> startingPoints;
    public final Set<String> manualInstrumentation;
    public final Set<String> traceClasses;
    public final Set<String> rpcClasses;
    public final Set<String> httpSendClasses;
    public final Set<String> httpRecvClasses;
    public final Set<String> isolateClasses;
    public final Set<String> ioClasses;
    public final Map<String, String> sedaQueueMap;
    public final Map<String, String> sedaWorkerMap;
    public final Set<String> entryClasses;
    public final Set<String> trackInitClasses;
    public boolean isSimpleInstrumentation = false;
    public boolean isStateBWEnabled = false;

    public ClassFilterHelper(String configPath) {
        this.configPath = configPath;
        this.blackPilotFuncList = new HashSet<>(SootUtils.getListFromProperty(configPath, PropertyType.BLACK_PILOTFUNC_LIST));
        this.whitePilotFuncList= new HashSet<>(SootUtils.getListFromProperty(configPath, PropertyType.WHITE_PILOTFUNC_LIST));
        this.stateWhiteList = new HashSet<>(SootUtils.getListFromProperty(configPath, PropertyType.STATE_WHITELIST_CLASS));
        this.stateBlackList = new HashSet<>(SootUtils.getListFromProperty(configPath, PropertyType.STATE_BLACKLIST_CLASS));
        this.manualInstrumentation = new HashSet<>(SootUtils.getListFromProperty(configPath, PropertyType.MANUAL_INSTRUMENTATION));
        this.startingPoints = new LinkedHashSet<>(SootUtils.getListFromProperty(configPath, PropertyType.START_POINTS));
        this.traceClasses = new HashSet<>(SootUtils.getListFromProperty(configPath, PropertyType.TRACE_CLASSES));
        this.rpcClasses = new HashSet<>(SootUtils.getListFromProperty(configPath, PropertyType.RPC_CLASSES));
        this.httpSendClasses = new HashSet<>(SootUtils.getListFromProperty(configPath, PropertyType.HTTP_SEND_CLASSES));
        this.httpRecvClasses = new HashSet<>(SootUtils.getListFromProperty(configPath, PropertyType.HTTP_RECV_CLASSES));
        this.isolateClasses = new HashSet<>(SootUtils.getListFromProperty(configPath, PropertyType.ISOLATE_CLASSES));
        this.ioClasses = new HashSet<>(SootUtils.getListFromProperty(configPath, PropertyType.IO_CLASSES));
        this.sedaQueueMap = new HashMap<>();
        this.sedaWorkerMap = new HashMap<>();
        this.entryClasses = new HashSet<>(SootUtils.getListFromProperty(configPath, PropertyType.ENTRY_CLASS));
        this.ctxTreeBlackList = new HashSet<>(SootUtils.getListFromProperty(configPath, PropertyType.CTX_TREE_BLACK_LIST));
        this.isSimpleInstrumentation = !new HashSet<>(SootUtils.getListFromProperty(configPath, PropertyType.SIMPLE_INSTRUMENTATION)).isEmpty();
        this.trackInitClasses = new HashSet<>(SootUtils.getListFromProperty(configPath, PropertyType.TRACK_INIT_CLASSES));

        Set<String> sedaQueueList = SootUtils.getListFromProperty(configPath, PropertyType.SEDA_QUEUE);
        for( String queue : sedaQueueList) {
            String[] parts = queue.split(":");
            if (parts.length == 2) {
                sedaQueueMap.put(parts[0].trim(), parts[1].trim());
            } else {
                LOG.warn("Invalid SEDA queue format: {}", queue);
            }
        }

        Set<String> sedaWorkerList = SootUtils.getListFromProperty(configPath, PropertyType.SEDA_WORKER);
        for (String worker : sedaWorkerList) {
            String[] parts = worker.split(":");
            if (parts.length == 2) {
                sedaWorkerMap.put(parts[0].trim(), parts[1].trim());
            } else {
                LOG.warn("Invalid SEDA worker format: {}", worker);
            }
        }

    }

    public static boolean shouldSkip(SootClass sc){
        if(sc.isInterface() || sc.isPhantom() ){
            return true;
        }
        return false;
    }

    public boolean isStateBlackWhiteListEnabled(){
        return !stateBlackList.isEmpty() || !stateWhiteList.isEmpty();
    }

    public boolean isStateWhiteListClass(SootClass sc){
        return isClassInList(sc, stateWhiteList);
    }

    public boolean isStateBlackListClass(SootClass sc){
        return isClassInList(sc, stateBlackList);
    }

    public boolean isBlackListPilotFuncClass(SootClass sc){
        return isClassInList(sc, blackPilotFuncList);
    }

    public boolean isContextTrackingBlackListClass(SootClass sc){
        return isClassInList(sc, ctxTreeBlackList);
    }

    public boolean isIsolateClass(SootClass sc){
        return isClassEqualsList(sc, isolateClasses);
    }
    public boolean isIoClass(SootClass sc){
        return isClassEqualsList(sc, ioClasses);
    }

    public boolean isContainsIoClass(SootClass sc){
        return isClassInList(sc, ioClasses);
    }

    public boolean isManuallyInstrumentedClass(SootClass sc){
        return isClassEqualsList(sc, manualInstrumentation);
    }

    public boolean isTraceClass(SootClass sc) {
        return isClassEqualsList(sc, traceClasses);
    }

    public boolean isRpcClass(SootClass sc) {
        return isClassEqualsList(sc, rpcClasses);
    }

    private boolean isClassEqualsList(SootClass sc, Set<String> list) {
        String className = sc.getName();
        for (String pattern : list) {
            if (pattern.isEmpty()) {
                continue;
            }
            if (className.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean isClassInList(SootClass sc, Set<String> list) {
        String className = sc.getName();
        for (String pattern : list) {
            if (pattern.isEmpty()) {
                continue;
            }
            if (className.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    public boolean isWhiteListPilotFuncClass(SootClass sc) {
        return isClassInList(sc, whitePilotFuncList);
    }


    public Set<String> getStartingPoints() {
        return new LinkedHashSet<>(startingPoints);
    }

}