package edu.uva.liftlab.pilot.util;

public class Constants {

    public static final String DRY_RUN_SUFFIX = "$dryRun";

    public static final String ORIGINAL_SUFFIX = "$original";
    public static final String INSTRUMENTATION_SUFFIX = "$instrumentation";

    public static final String INSTRUMENTATION_SUFFIX_FOR_INIT_FUNC = "_$instrumentation";

    public static final String BLACK_PILOTFUNC_LIST = "blacklist_pilotfunc_classes";

    public static final String STATE_BW_ENABLEDD = "state_enablebw";

    public static final String STATE_BLACK_CLASS = "blacklist_state_classes";

    public static final String STATE_WHITE_CLASS = "whitelist_state_classes";

    public static final String WHITE_PILOTFUNC_LIST = "whitelist_pilotfunc_classes";

    public static final String MANUAL_INSTRUMENTATION = "manual_ignore_classes";

    public static final String START_METHODS = "startpoint_methods";

    public static final String TRACE_CLASSES="trace_classes";

    public static final String RPC_CLASSES = "rpc_classes";

    public static final String HTTP_SEND_CLASSES = "http_send_classes";

    public static final String HTTP_RECV_CLASSES = "http_recv_classes";

    public static final String IO_CLASSES = "io_classes";

    public static final String SEDA_QUEUE = "seda_queue";

    public static final String SEDA_WORKER = "seda_worker";

    public static final String CTX_TREE_BLACK_LIST = "ctxTreeBlacklist_classes";

    public static final String ISOLATE_CLASSES = "isolate_classes";

    public static final String ENTRY_CLASS = "entry_classes";

    public static final String DRY_RUN = "dryrun";

    public static final String SHADOW= "$shadow";

    public static final String SHADOW_FIELD= "isShadow";

    public static final String ORIGINAL_THREAD_ID_FIELD = "originalThreadId";

    public static final String WRAP_CONTEXT_CLASS_NAME = "org.pilot.WrapContext";

    public static final String UTIL_CLASS_NAME = "org.pilot.PilotUtil";

    public static final String IS_FAST_FORWARD_BAGGAGE = "isFastForward";

    public static final boolean debug = false;

    public static final String mode = "SEDA";

    public static final String STATE_ISOLATION_CLASS = "org.pilot.State";

    public static final String SHOULD_BE_CONTEXT_WRAP_METHOD_SIGNATURE = "boolean shouldBeContextWrap(java.lang.Runnable,java.util.concurrent.Executor)";

    public static final String PILOT_UTIL_CLASS_NAME = "org.pilot.PilotUtil";

    public static final String LAMBDA_BOOT_STRAP = "bootstrap$";

    public static final String SIMPLE_INSTRUMENTATION = "simple_instrumentation";

    public static final String CONTEXT_TYPE = "io.opentelemetry.context.Context";

    public static final String TRACK_INIT_CLASSES = "pilottrack_init_class";

}
