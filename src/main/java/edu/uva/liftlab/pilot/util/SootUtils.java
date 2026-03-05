package edu.uva.liftlab.pilot.util;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

import edu.uva.liftlab.pilot.isolation.stateredirection.ClassFilterHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.baf.BafASMBackend;
import soot.jimple.*;
import soot.options.Options;
import soot.tagkit.*;
import soot.toolkits.scalar.LocalPacker;
import soot.toolkits.scalar.UnusedLocalEliminator;
import soot.util.Chain;
import soot.util.JasminOutputStream;
import soot.validation.ValidationException;

import static edu.uva.liftlab.pilot.distributedtracing.CallablePropagator.NEED_DRY_RUN_TRACE;
import static edu.uva.liftlab.pilot.transformer.ExperimentCtxTransformer.ENTRY_RECOVERY_POINT;
import static edu.uva.liftlab.pilot.util.Constants.*;


/**
 * A collection of helper functions for using Soot
 */
public class SootUtils {
    public static boolean verbose = false;

    public static Set<SootMethod> shouldInstrumentedMethods = new HashSet<>();

    private static final Logger LOG = LoggerFactory.getLogger(SootUtils.class);

    public static boolean isObviouslySimpleMethod(SootMethod method) {
        String name = method.getName();
        String signature = method.getSignature();

        if (name.equals("toString") ||
                name.equals("hashCode") ||
                name.equals("equals") ||
                name.equals("valueOf") ||
                name.equals("compareTo") ||
                signature.contains("java.lang.String: java.lang.String concat(java.lang.String)") ||
                signature.contains("java.lang.StringBuilder: java.lang.StringBuilder append(") ||
                signature.contains("java.lang.Math")) {
            return true;
        }
        return false;
    }

//    public static final HashMap<PropertyType, String> PROPERTY_TYPE_TO_STRING = new HashMap<PropertyType, String>() {{
//        put(PropertyType.BLACK_LIST, BLACK_LIST);
//        put(PropertyType.WHITE_LIST, WHITE_LIST);
//        put(PropertyType.MANUAL_INSTRUMENTATION, MANUAL_INSTRUMENTATION);
//        put(PropertyType.START_POINTS, START_METHODS);
//        put(PropertyType.TRACE_CLASSES, TRACE_CLASSES);
//        put(PropertyType.HTTP_SEND_CLASSES, HTTP_SEND_CLASSES);
//        put(PropertyType.HTTP_RECV_CLASSES, HTTP_RECV_CLASSES);
//        put(PropertyType.RPC_CLASSES, RPC_CLASSES);
//        put(PropertyType.ISOLATE_CLASSES, ISOLATE_CLASSES);
//        put(PropertyType.IO_CLASSES, IO_CLASSES);
//        put(PropertyType.SEDA_QUEUE, SEDA_QUEUE);
//        put(PropertyType.SEDA_WORKER, SEDA_WORKER);
//        put(PropertyType.ENTRY_CLASS, ENTRY_CLASS);
//        put(PropertyType.STATE_CLASS, STATE_CLASS);
//        put(PropertyType.CTX_TREE_BLACK_LIST, CTX_TREE_BLACK_LIST);
//        put(PropertyType.SIMPLE_INSTRUMENTATION, SIMPLE_INSTRUMENTATION);
//    }};

    public static final HashMap<PropertyType, String> PROPERTY_TYPE_TO_STRING = new HashMap<PropertyType, String>() {{
        put(PropertyType.BLACK_PILOTFUNC_LIST, BLACK_PILOTFUNC_LIST);
        put(PropertyType.WHITE_PILOTFUNC_LIST, WHITE_PILOTFUNC_LIST);

        put(PropertyType.STATE_BW_ENABLED, STATE_BW_ENABLEDD);
        put(PropertyType.STATE_BLACKLIST_CLASS, STATE_BLACK_CLASS);
        put(PropertyType.STATE_WHITELIST_CLASS, STATE_WHITE_CLASS);

        put(PropertyType.MANUAL_INSTRUMENTATION, MANUAL_INSTRUMENTATION);
        put(PropertyType.START_POINTS, START_METHODS);
        put(PropertyType.TRACE_CLASSES, TRACE_CLASSES);
        put(PropertyType.HTTP_SEND_CLASSES, HTTP_SEND_CLASSES);
        put(PropertyType.HTTP_RECV_CLASSES, HTTP_RECV_CLASSES);
        put(PropertyType.RPC_CLASSES, RPC_CLASSES);
        put(PropertyType.ISOLATE_CLASSES, ISOLATE_CLASSES);
        put(PropertyType.IO_CLASSES, IO_CLASSES);
        put(PropertyType.SEDA_QUEUE, SEDA_QUEUE);
        put(PropertyType.SEDA_WORKER, SEDA_WORKER);
        put(PropertyType.ENTRY_CLASS, ENTRY_CLASS);
        put(PropertyType.CTX_TREE_BLACK_LIST, CTX_TREE_BLACK_LIST);
        put(PropertyType.SIMPLE_INSTRUMENTATION, SIMPLE_INSTRUMENTATION);
        put(PropertyType.TRACK_INIT_CLASSES, TRACK_INIT_CLASSES);
    }};


    /**
     * Get line number of a Soot unit
     */

    public static InvokeExpr getInvokeExpr(Unit unit) {
        InvokeExpr invoke = null;

        if (unit instanceof AssignStmt) {
            AssignStmt assign = (AssignStmt) unit;
            if (assign.getRightOp() instanceof InvokeExpr) {
                invoke = (InvokeExpr) assign.getRightOp();
            }
        }
        else if (unit instanceof Stmt) {
            Stmt stmt = (Stmt) unit;
            if (stmt.containsInvokeExpr()) {
                invoke = stmt.getInvokeExpr();
            }
        }
        return invoke;
    }
    public static boolean classShouldBeInstrumented( SootClass sootClass){
        if(sootClass.isEnum() || sootClass.isInterface()){
            return false;
        }
        return true;
    }

    public static String getInstrumnentationMethodName(SootMethod method){
        if(method.isConstructor()){
            assert method.getName().equals("<init>");
            return "init"+INSTRUMENTATION_SUFFIX_FOR_INIT_FUNC;
        } else if(method.isStaticInitializer()){
            assert method.getName().equals("<clinit>");
            return "clinit"+INSTRUMENTATION_SUFFIX_FOR_INIT_FUNC;
        }else{
            return method.getName()+INSTRUMENTATION_SUFFIX;
        }
    }

    public static String getOriginalMethodName(SootMethod method){
        if(method.isConstructor()){
            assert method.getName().equals("<init>");
            return "init"+ORIGINAL_SUFFIX;
        } else if(method.isStaticInitializer()){
            assert method.getName().equals("<clinit>");
            return "clinit"+ORIGINAL_SUFFIX;
        }else{
            return method.getName()+ORIGINAL_SUFFIX;
        }
    }


    public static boolean isDryRunClass(SootClass sootClass){
        return sootClass.getName().contains(DRY_RUN);
    }

    public static boolean dryRunMethodshouldBeInstrumented(SootMethod method, SootClass sootClass) {
        if (method.isStaticInitializer() ||
                method.isConstructor() ||
                method.isNative() ||
                method.isAbstract() ||
                sootClass.isEnum() ||
                sootClass.isInterface()
                || !method.getName().endsWith(INSTRUMENTATION_SUFFIX)
        ) {
            return false;
        }
        return true;
    }

    public static String getDryRunTraceFieldName(SootClass cls) {
        return NEED_DRY_RUN_TRACE + cls.getName().replace(".", "");
    }

    public static boolean originalMethodShouldBeInstrumented(SootMethod originalMethod, SootClass sootClass) {
        if (originalMethod.isStaticInitializer() ||  originalMethod.isNative() || originalMethod.isAbstract()
                || sootClass.isEnum() || originalMethod.isConstructor()){
            return false;
        }
        return true;
    }
    public static int getLine(Unit unit) {
        int line = -1;
        LineNumberTag tag = (LineNumberTag) unit.getTag("LineNumberTag");
        if (tag != null) {
            line = tag.getLineNumber();
        }
        return line;
    }

    public static Unit getLastIdentityStmt(Body body) {
        boolean foundNonIdentity = false;
        Unit lastIdentityStmt = null;

        for (Unit u : body.getUnits()) {
            if (!foundNonIdentity) {
                if (u instanceof IdentityStmt) {
                    lastIdentityStmt = u;
                } else {
                    foundNonIdentity = true;
                }
            }
        }
        return lastIdentityStmt;
    }

    public static Unit getFirstNonIdentityStmt(Body body) {
        boolean foundNonIdentity = false;
        Unit firstNonIdentityStmt = null;

        for (Unit u : body.getUnits()) {
            if (!foundNonIdentity) {
                if (u instanceof IdentityStmt) {

                } else {
                    foundNonIdentity = true;
                    firstNonIdentityStmt = u;
                }
            }
        }
        return foundNonIdentity ? firstNonIdentityStmt : null;
    }

    public static void validateVariables(Body body){
        try {
            
            LocalPacker.v().transform(body);
            UnusedLocalEliminator.v().transform(body);

            
            body.validate();
        } catch (Exception e) {
            System.err.println("Warning: Failed to optimize body: " + e.getMessage());
        }
    }

    public static Unit getLastIdentityUnit(Body body) {
        Unit lastIdentity = null;
        for (Unit unit : body.getUnits()) {
            if (unit instanceof IdentityStmt) {
                lastIdentity = unit;
            } else {
                
                
                break;
            }
        }
        return lastIdentity;
    }

    public static void getShouldInstrumentedMethodForCtxTree(ClassFilterHelper classFilterHelper) {
        for(SootClass sc: Scene.v().getApplicationClasses()){
            // Skip interfaces and phantom classes
            if(classFilterHelper.isContextTrackingBlackListClass(sc) && !classFilterHelper.isTraceClass(sc) && !classFilterHelper.isIsolateClass(sc) && !classFilterHelper.isManuallyInstrumentedClass(sc)){
                continue;
            }


            for (SootMethod method : sc.getMethods()) {
                LOG.info("Evaluating method {} for context tracking instrumentation", method.getSignature());
                if (!method.isConcrete() || method.getName().contains("$") || method.isStaticInitializer() || method.isConstructor()) {
                    continue;
                }
                if(method.getName().contains(ENTRY_RECOVERY_POINT)){
                    continue;
                }


                LOG.info("Method {} is being evaluated for context tracking instrumentation", method.getSignature());
                if(method.getName().contains("run") || method.getName().contains("call") || method.getName().contains("execute")){
                    LOG.info("Method {} selected for context tracking instrumentation because it is a run/call method", method.getSignature());
                    SootUtils.shouldInstrumentedMethods.add(method);
                    continue;
                }

                if(isMethodComplexEnoughForInstrumentation(method) && !skipInstrumentationForCtxTree(method)) {
                    LOG.info("Method {} selected for context tracking instrumentation", method.getSignature());
                    SootUtils.shouldInstrumentedMethods.add(method);
                }
                SootUtils.shouldInstrumentedMethods.add(method);
            }
        }
    }

    public static boolean skipInstrumentationForCtxTree(SootMethod method){
        Body body = method.retrieveActiveBody();
        Unit firstNonIdentity = getFirstNonIdentityStmt(body);
        for (Trap trap : body.getTraps()) {
            Unit trapBegin = trap.getBeginUnit();
            if (trapBegin == firstNonIdentity) {
                LOG.debug("Skipping method {} because it starts with a try block", method.getSignature());
                return true;
            }

            boolean inTrap = false;
            for (Unit u = trapBegin; u != trap.getEndUnit(); u = body.getUnits().getSuccOf(u)) {
                if (u == firstNonIdentity) {
                    inTrap = true;
                    break;
                }
            }
            if (inTrap) {
                LOG.debug("Skipping method {} because first statement is in try block", method.getSignature());
                return inTrap;
            }
        }
        return false;
    }

    public static boolean isMethodComplexEnoughForInstrumentation(SootMethod method) {
        if (!method.hasActiveBody()) return false;

        LOG.info("Analyzing method for instrumentation: {}", method.getSignature());

        if(method.getName().equals("run") && method.getParameterCount() == 0){
            return true;
        }

        Body body = method.retrieveActiveBody();
        PatchingChain<Unit> units = body.getUnits();

        
        int unitCount = units.size();
        int meaningfulInvokes = 0;
        boolean hasLoops = false;
        int branchCount = 0;
        int trapCount = body.getTraps().size();

        for (Unit unit : units) {
            // Check for method calls
            InvokeExpr invoke = null;
            if (unit instanceof InvokeStmt) {
                invoke = ((InvokeStmt) unit).getInvokeExpr();
            } else if (unit instanceof AssignStmt) {
                Value rightOp = ((AssignStmt) unit).getRightOp();
                if (rightOp instanceof InvokeExpr) {
                    invoke = (InvokeExpr) rightOp;
                }
            }

            if (invoke != null) {
                String invokedClass = invoke.getMethod().getDeclaringClass().getName();
                String invokedMethod = invoke.getMethod().getName();

                // Count as meaningful if not a simple utility call
                if (!invokedClass.startsWith("java.lang.String") &&
                        !invokedClass.startsWith("java.lang.StringBuilder") &&
                        !invokedClass.startsWith("java.util.logging") &&
                        !invokedClass.startsWith("org.slf4j") &&
                        !invokedMethod.equals("toString") &&
                        !invokedMethod.equals("hashCode") &&
                        !invokedMethod.equals("equals") &&
                        !invokedMethod.startsWith("get") &&
                        !invokedMethod.startsWith("set") &&
                        !invokedMethod.startsWith("is")) {
                    meaningfulInvokes++;
                }
            }

            // Check for loops
            if (unit instanceof GotoStmt) {
                GotoStmt gotoStmt = (GotoStmt) unit;
                Unit target = gotoStmt.getTarget();
                boolean isBackwardJump = false;
                for (Unit u : units) {
                    if (u == target) {
                        isBackwardJump = true;
                        break;
                    }
                    if (u == unit) {
                        break;
                    }
                }
                if (isBackwardJump) {
                    hasLoops = true;
                }
            }

            // Check for conditional branching
            if (unit instanceof IfStmt) {
                branchCount++;
            } else if (unit instanceof TableSwitchStmt || unit instanceof LookupSwitchStmt) {
                branchCount += 3;
            }
        }

        
        List<String> metConditions = new ArrayList<>();

        
        boolean shouldInstrument = false;

        
        if (unitCount >= 100) {
            metConditions.add(String.format("sufficient units (%d >= 100)", unitCount));
            shouldInstrument = true;
        }

        
        if (meaningfulInvokes >= 8) {
            metConditions.add(String.format("sufficient meaningful invokes (%d >= 8)", meaningfulInvokes));
            shouldInstrument = true;
        }

        
        if (branchCount >= 3 || hasLoops) {
            metConditions.add(String.format("sufficient control flow (branches: %d >= 3, has loops: %b)",
                    branchCount, hasLoops));
            shouldInstrument = true;
        }

        
        if (trapCount >= 3) {
            metConditions.add(String.format("sufficient exception handlers (%d >= 3)", trapCount));
            shouldInstrument = true;
        }

        
        if (shouldInstrument) {
            LOG.debug("Instrumenting {} - met conditions: {}, metrics: units={}, invokes={}, branches={}, loops={}, traps={}",
                    method.getSignature(),
                    String.join(", ", metConditions),
                    unitCount, meaningfulInvokes, branchCount, hasLoops, trapCount);
        } else {
            LOG.debug("Skipping {} - no conditions met. Metrics: units={} (<100), invokes={} (<8), branches={} (<3), loops={}, traps={} (<3)",
                    method.getSignature(), unitCount, meaningfulInvokes, branchCount, hasLoops, trapCount);
        }

        return shouldInstrument;
    }



    /**
     * Add a new phase into a phase pack in Soot
     *
     * @return the new phase added
     */
    public static Transform addNewTransform(String phasePackName, String phaseName,
                                            Transformer transformer) {
        LOG.info("phasePackName is "+phasePackName + " phaseName is " + phaseName);
        Transform phase = new Transform(phaseName, transformer);
        phase.setDeclaredOptions("enabled");
        phase.setDefaultOptions("enabled:false");
        PackManager.v().getPack(phasePackName).add(phase);
        return phase;
    }

    /**
     * check if a type is primtype (int, bool..) or string
     */
    public static boolean ifPrimJavaType(Type type) {
        return type.equals(ShortType.v()) || type.equals(ByteType.v()) ||
                type.equals(BooleanType.v()) || type.equals(CharType.v()) ||
                type.equals(IntType.v()) || type.equals(LongType.v()) ||
                type.equals(FloatType.v()) || type.equals(DoubleType.v()) ||
                type.equals(NullType.v());
    }

    public static boolean ifCollectionJavaType(Type type) {

        if (
                SootUtils.ifTypeImplementInterface(type, "java.util.Set")
                        ||
                        SootUtils.ifTypeImplementInterface(type, "java.util.List")
                        ||
                        SootUtils.ifTypeImplementInterface(type, "java.util.Map")) {
            return true;
        }

        return false;
    }

    public static boolean ifArrayJavaType(Type type) {
        if (type.getArrayType() != null) {
            return true;
        }

        return false;
    }

    /**
     * check if type implements a given interface
     */
    public static boolean ifTypeImplementInterface(Type type, String interfaceName) {
        SootClass c = Scene.v().loadClassAndSupport(type.toString());
        if (type.toString().equals(interfaceName)
                || c.implementsInterface(interfaceName)) {
            return true;
        }

        if (c.isInterface()) {
            for (SootClass z : Scene.v().getActiveHierarchy().getSuperinterfacesOf(c)) {
                if (z.getName().equals(interfaceName)) {
                    return true;
                }
            }
        } else {
            SootClass it = c;
            while (it.hasSuperclass()) {
                it = it.getSuperclass();
                if (it.implementsInterface(interfaceName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * get the base from invoker expr, e.g. base.doIO() -> base
     *
     * @param stmt invokerstmt
     * @return base
     */
    public static Value getBaseFromInvokerExpr(Stmt stmt) {
        Value base = null;
        if (stmt.getInvokeExpr() instanceof VirtualInvokeExpr) {
            base = ((VirtualInvokeExpr) stmt.getInvokeExpr()).getBase();
        } else if (stmt.getInvokeExpr() instanceof SpecialInvokeExpr) {
            base = ((SpecialInvokeExpr) stmt.getInvokeExpr()).getBase();
        } else if (stmt.getInvokeExpr() instanceof InterfaceInvokeExpr) {
            base = ((InterfaceInvokeExpr) stmt.getInvokeExpr()).getBase();
        } else {
            // happens for staticinvoke

            //throw new RuntimeException(
            //        "InvokeExpr type not supported\n stmt:" + stmt);
        }
        return base;
    }


    /**
     * get method signature (class name + method name)
     */
    public static String getMethodSignature(SootMethod method) {
        return method.getDeclaringClass().getName() + "@" + method.getNumberedSubSignature()
                .toString();
    }

    /**
     * get sub classes of a class
     *
     * @return list of sub class
     */
    public static List<SootClass> getSubClass(SootClass superClass) {
        List<SootClass> lst = new ArrayList<>();
        for (SootClass c : Scene.v().getApplicationClasses()) {
            SootClass it = c;
            if (it.hasSuperclass()) {
                if (it.getSuperclass().getName().equals(superClass.getName())) {
                    lst.add(c);
                }
            }
        }

        return lst;
    }

    public static List<SootClass> getImpls(SootClass interfaceClass) {
        List<SootClass> lst = new ArrayList<>();
        for (SootClass c : Scene.v().getApplicationClasses()) {
            SootClass it = c;
            if (it.implementsInterface(interfaceClass.getName())) {
                lst.add(c);
            }
        }

        return lst;
    }

    private static void outputSootClassJimple(SootClass sootClass, PrintStream out) {

        out.println("public class " + sootClass.toString());
        out.println("{");
        for (SootField f : sootClass.getFields()) {
            out.println("    " + f.getDeclaration().toString());
        }
        out.println("");
        for (SootMethod m : sootClass.getMethods()) {
            if (m.hasActiveBody()) {
                out.println("    " + m.getActiveBody().toString());
            } else {
                out.println("    " + m.toString() + " [no active body found]");
            }
        }
        out.println("}");
    }

    public static void printBodyJimple(Body body) {
        System.out.println(body.toString());
    }


    public static void printSootClassJimple(SootClass sootClass) {
        outputSootClassJimple(sootClass, System.out);
    }

    public static void dumpSootClassJimple(SootClass sootClass, String... fileNameSuffix) {
        PrintStream out;
        String fileName = SourceLocator.v().getFileNameFor(sootClass, Options.output_format_jimple);
        if(fileNameSuffix.length>0)
            fileName += "."+fileNameSuffix[0];
        File file = new File(fileName);
        file.getParentFile().mkdirs();
        try {
            LOG.info("Writing class " + sootClass.getName() + " to " + fileName);
            out = new PrintStream(file);
            outputSootClassJimple(sootClass, out);

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    public static void dumpBodyJimple(Body body, String... fileNameSuffix) {
        String fileName =
                body.getMethod().getDeclaringClass().getName() + "_" + body.getMethod().getName();
        if(fileNameSuffix.length>0)
            fileName += "."+fileNameSuffix[0];
        fileName += ".jimple";
        LOG.info(
                "Writing method " + body.getMethod().getName() + " to " + SourceLocator.v()
                        .getOutputDir() + "/" + fileName);
        File file = new File(SourceLocator.v().getOutputDir(), fileName);
        file.getParentFile().mkdirs();
        try {
            PrintStream out = new PrintStream(file);
            out.println(body.toString());
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    public static boolean dumpSootClass(SootClass sClass) {
        // Since it is .class we are generating, we must validate its integrity before dumping.
        sClass.validate();
        String fileName = SourceLocator.v().getFileNameFor(sClass, Options.output_format_class);
        LOG.info("Writing class " + sClass.getName() + " to " + fileName);
        File file = new File(fileName);
        file.getParentFile().mkdirs();
        try {
            OutputStream streamOut = new FileOutputStream(file);
            if (Options.v().jasmin_backend()) {
                streamOut = new JasminOutputStream(streamOut);
            }
            PrintWriter writerOut = new PrintWriter(
                    new OutputStreamWriter(streamOut));
            if (!Options.v().jasmin_backend()) {
                new BafASMBackend(sClass, Options.v().java_version()).generateClassFile(streamOut);
            } else {
                JasminClass jasminClass = new JasminClass(sClass);
                jasminClass.print(writerOut);
            }
            writerOut.flush();
            streamOut.close();
            writerOut.close();

            return true;
        } catch (Exception e) {
            LOG.error("Exception when writing class "+ sClass.getName());
            e.printStackTrace();

            //due to Soot's some internal bad usages of exception msg recording,
            //sometimes we need to print more here
            if(e instanceof ValidationException)
                LOG.info("AdditionalExceptionMsg: "+((ValidationException)e).getConcerned()+" "+((ValidationException)e).getRawMessage());

            try {
                boolean result = Files.deleteIfExists(file.toPath());
            }catch (IOException ex){
                //try your best to clean, if not an empty class will replace target class
                LOG.error("Exception when cleaning class "+ sClass.getName());
            }

            return false;
        }
    }

    public static void tryDumpSootClass(SootClass sClass) {
        try {
            SootUtils.dumpSootClass(sClass);
        } catch (Exception e) {
            e.printStackTrace();
            SootUtils.printSootClassJimple(sClass);
            System.exit(-1);
        }
    }

    public static void SecureExportSootClass(SootClass sClass) {
        //SootUtils.printSootClassJimple(contextClass);
        SootUtils.dumpSootClassJimple(sClass);
        sClass.validate();
        SootUtils.tryDumpSootClass(sClass);
    }

    public static boolean hasTestAnnotation(SootMethod method) {
        boolean hasTestAnnotation = false;
        VisibilityAnnotationTag tag = (VisibilityAnnotationTag) method.getTag("VisibilityAnnotationTag");
        if (tag != null) {
            for (AnnotationTag annotation : tag.getAnnotations()) {
                System.out.println(annotation.getType());
                if (annotation.getType().equals("Lorg/junit/Test;")) {
                    hasTestAnnotation = true;
                    break;
                }
            }
        }
        return hasTestAnnotation;
    }

    public static Stmt findFirstNonIdentityStmt(Body body) {
        for(Unit unit:body.getUnits())
        {
            if(!(unit instanceof IdentityStmt))
            {
                LOG.debug("findFirstNonIdentityStmt "+unit.toString());
                return (Stmt) unit;
            }
        }
        throw new RuntimeException("cannot find non-IdentityStmt");
    }

    public static List<IdentityStmt> findAllIdentityStmts(Body body) {
        List<IdentityStmt> ret = new ArrayList<>();
        for(Unit unit:body.getUnits())
        {
            if((unit instanceof IdentityStmt))
            {
                ret.add((IdentityStmt)unit);
            }
        }
        return ret;
    }

    public static Type boxBasicJavaType(Type type) {
        if (type.equals(BooleanType.v())) {
            return RefType.v("java.lang.Boolean");
        } else if (type.equals(ByteType.v())) {
            return RefType.v("java.lang.Byte");
        } else if (type.equals(CharType.v())) {
            return RefType.v("java.lang.Character");
        } else if (type.equals(FloatType.v())) {
            return RefType.v("java.lang.Float");
        } else if (type.equals(IntType.v())) {
            return RefType.v("java.lang.Integer");
        } else if (type.equals(LongType.v())) {
            return RefType.v("java.lang.Long");
        } else if (type.equals(ShortType.v())) {
            return RefType.v("java.lang.Short");
        } else if (type.equals(DoubleType.v())) {
            return RefType.v("java.lang.Double");
        } else if (type.equals(NullType.v())) {
            return RefType.v("java.lang.Object");
        } else {
            //throw new RuntimeException("New type not supported:" + type.toString());
            return type;
        }
    }

    private static String unboxJavaType(RefType boxedType)
    {
        String primTypeName = null;
        if (boxedType.getClassName().equals("java.lang.Boolean")) {
            primTypeName = "boolean";
        } else if (boxedType.getClassName().equals("java.lang.Byte")) {
            primTypeName = "byte";
        } else if (boxedType.getClassName().equals("java.lang.Character")) {
            primTypeName = "char";
        } else if (boxedType.getClassName().equals("java.lang.Float")) {
            primTypeName = "float";
        } else if (boxedType.getClassName().equals("java.lang.Integer")) {
            primTypeName = "int";
        } else if (boxedType.getClassName().equals("java.lang.Long")) {
            primTypeName = "long";
        } else if (boxedType.getClassName().equals("java.lang.Short")) {
            primTypeName = "short";
        } else if (boxedType.getClassName().equals("java.lang.Double")) {
            primTypeName = "double";
        }

        return primTypeName;
    }

    //to get the prim type out of boxed type: e.g. int a = (new Integer(5)).intValue();
    public static SootMethod getBackPrimMethodName(RefType boxedType) {
        String methodName = null;
        methodName = unboxJavaType(boxedType)+"Value";

        return boxedType.getSootClass().getMethodByName(methodName);
    }



    //to get the init method out of boxed type
    public static String getInitPrimMethodSignature(RefType boxedType) {
        String methodSig = null;
        methodSig = "void <init>("+unboxJavaType(boxedType)+")";

        return methodSig;
    }

    public static Constant getConstantForPrim(Type t)
    {
        if(t.equals(DoubleType.v()) || t.equals(RefType.v("java.lang.Double")))
            return DoubleConstant.v(0);
        else if(t.equals(FloatType.v()) || t.equals(RefType.v("java.lang.Float")))
            return FloatConstant.v(0);
        else if(t.equals(LongType.v())|| t.equals(RefType.v("java.lang.Long")))
            return LongConstant.v(0);
        else
            return IntConstant.v(0);
    }

    public static List<Unit> printLog(String message, LocalGeneratorUtil lg) {
        if (!shouldPrint(lg.getClassName())) {
            return new ArrayList<>();
        }

        List<Unit> units = new ArrayList<>();

        Local printStream = lg.generateLocalWithId(
                RefType.v("java.io.PrintStream"),
                "$printStream"
        );

        units.add(Jimple.v().newAssignStmt(
                printStream,
                Jimple.v().newStaticFieldRef(
                        Scene.v().makeFieldRef(
                                Scene.v().loadClassAndSupport("java.lang.System"),
                                "out",
                                RefType.v("java.io.PrintStream"),
                                true
                        )
                )
        ));


        units.add(Jimple.v().newInvokeStmt(
                Jimple.v().newVirtualInvokeExpr(
                        printStream,
                        Scene.v().makeMethodRef(
                                Scene.v().loadClassAndSupport("java.io.PrintStream"),
                                "println",
                                Collections.singletonList(RefType.v("java.lang.String")),
                                VoidType.v(),
                                false
                        ),
                        Collections.singletonList(StringConstant.v(message))
                )
        ));

        return units;
    }

    public static boolean shouldPrint(String classname){
        if(!verbose || !classname.contains("org.apache.cassandra.db.lifecycle.SSTableIntervalTree")){
            return false;
        }
        return true;
    }


    public static List<Unit> printLog4j(String message, LocalGeneratorUtil lg) {
        if (!shouldPrint(lg.getClassName())) {
            return new ArrayList<>();
        }
        
        StaticInvokeExpr dryRunLogExpr = Jimple.v().newStaticInvokeExpr(
                Scene.v().makeMethodRef(
                        Scene.v().loadClassAndSupport(PILOT_UTIL_CLASS_NAME),
                        "dryRunLog",
                        Collections.singletonList(RefType.v("java.lang.String")),
                        VoidType.v(),
                        true  
                ),
                Collections.singletonList(StringConstant.v(message))
        );

        List<Unit> units = new ArrayList<>();
        units.add(Jimple.v().newInvokeStmt(dryRunLogExpr));
        return units;
    }

    public static List<Unit> printValue(Local localVariable, LocalGeneratorUtil lg, String additionalMessage) {
        if (!shouldPrint(lg.getClassName())) {
            return new ArrayList<>();
        }
        List<Unit> units = new ArrayList<>();

        StaticInvokeExpr printMessageExpr = Jimple.v().newStaticInvokeExpr(
                Scene.v().makeMethodRef(
                        Scene.v().loadClassAndSupport(PILOT_UTIL_CLASS_NAME),
                        "dryRunLog",
                        Collections.singletonList(RefType.v("java.lang.String")),
                        VoidType.v(),
                        true
                ),
                Collections.singletonList(StringConstant.v(additionalMessage))
        );
        units.add(Jimple.v().newInvokeStmt(printMessageExpr));

        if (!(localVariable.getType() instanceof PrimType)) {
            Local tmpBool = lg.generateLocal(BooleanType.v());
            Local tmpString = lg.generateLocal(RefType.v("java.lang.String")); 

            units.add(Jimple.v().newAssignStmt(
                    tmpBool,
                    Jimple.v().newStaticInvokeExpr(
                            Scene.v().makeMethodRef(
                                    Scene.v().loadClassAndSupport("java.util.Objects"),
                                    "isNull",
                                    Collections.singletonList(RefType.v("java.lang.Object")),
                                    BooleanType.v(),
                                    true
                            ),
                            Collections.singletonList(localVariable)
                    )
            ));

            units.add(Jimple.v().newAssignStmt(
                    tmpString,
                    Jimple.v().newStaticInvokeExpr(
                            Scene.v().makeMethodRef(
                                    Scene.v().loadClassAndSupport("java.lang.String"),
                                    "valueOf",
                                    Collections.singletonList(BooleanType.v()),
                                    RefType.v("java.lang.String"),
                                    true
                            ),
                            Collections.singletonList(tmpBool)
                    )
            ));

            StaticInvokeExpr printNullCheckExpr = Jimple.v().newStaticInvokeExpr(
                    Scene.v().makeMethodRef(
                            Scene.v().loadClassAndSupport(PILOT_UTIL_CLASS_NAME),
                            "dryRunLog",
                            Collections.singletonList(RefType.v("java.lang.String")),
                            VoidType.v(),
                            true
                    ),
                    Collections.singletonList(tmpString)
            );
            units.add(Jimple.v().newInvokeStmt(printNullCheckExpr));

        } else {

            Local tmpString = lg.generateLocal(RefType.v("java.lang.String"));

            units.add(Jimple.v().newAssignStmt(
                    tmpString,
                    Jimple.v().newStaticInvokeExpr(
                            Scene.v().makeMethodRef(
                                    Scene.v().loadClassAndSupport("java.lang.String"),
                                    "valueOf",
                                    Collections.singletonList(localVariable.getType()),
                                    RefType.v("java.lang.String"),
                                    true
                            ),
                            Collections.singletonList(localVariable)
                    )
            ));

            
            StaticInvokeExpr printValueExpr = Jimple.v().newStaticInvokeExpr(
                    Scene.v().makeMethodRef(
                            Scene.v().loadClassAndSupport(PILOT_UTIL_CLASS_NAME),
                            "dryRunLog",
                            Collections.singletonList(RefType.v("java.lang.String")),
                            VoidType.v(),
                            true
                    ),
                    Collections.singletonList(tmpString)
            );
            units.add(Jimple.v().newInvokeStmt(printValueExpr));
        }

        return units;
    }

    public static Set<String> getListFromProperty(String configFile, Enum<PropertyType> type){
        LOG.info("Loading config file: " + configFile);
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(configFile)) {
            props.load(fis);
        } catch (IOException e) {
            LOG.info("Cannot load config file");
        }
        String strList;
        Set<String> res = new HashSet<>();

        try{
            String name=PROPERTY_TYPE_TO_STRING.get(type);
            strList= props.getProperty(name);
            res = new HashSet<>(
                    Arrays.asList(strList.split(","))
            );
        }catch (RuntimeException e){
            LOG.warn("No pre-defined property: " + type.toString());
            e.printStackTrace();
            return new HashSet<>();
        }
        return res;
    }

    public static boolean isClassInList(String className, Set<String> classList){
        for(String s: classList){
            if(className.contains(s)){
                return true;
            }
        }
        return false;
    }


    public static boolean isSynchronizationMethod(String className, String methodName) {
        return className.contains("Lock") ||
                className.contains("Semaphore") ||
                className.contains("CountDownLatch") ||
                className.contains("CyclicBarrier") ||
                className.contains("Phaser") ||
                className.contains("Exchanger") ||
                className.contains("BlockingQueue") ||
                className.contains("Concurrent") ||
                className.contains("AtomicReference") ||
                className.contains("AtomicInteger") ||
                className.contains("AtomicLong") ||
                className.contains("AtomicBoolean") ||
                methodName.equals("wait") ||
                methodName.equals("notify") ||
                methodName.equals("notifyAll") ||
                methodName.equals("join") ||
                methodName.contains("synchronized");
    }

    public static boolean isAsyncMethod(String className, String methodName) {
        // Check class names related to async operations
        if (className.contains("CompletableFuture") ||
                className.contains("Future") ||
                className.contains("Executor") ||
                className.contains("Async") ||
                className.contains("RxJava") ||
                className.contains("Reactor")) {
            return true;
        }

        // Check method names related to async operations
        if (methodName.contains("async") ||
                methodName.contains("schedule") ||
                methodName.equals("submit") ||
                methodName.equals("execute") ||
                methodName.startsWith("publish") ||
                methodName.startsWith("subscribe")) {
            return true;
        }

        return false;
    }


    /**
     * Check if a method is related to network I/O
     */
    public static boolean isNetworkIOMethod(String className, String methodName) {
        return className.contains("Socket") ||
                className.contains("ServerSocket") ||
                className.contains("DatagramSocket") ||
                className.contains("InetAddress") ||
                className.contains("URL") ||
                className.contains("HttpClient") ||
                className.contains("HttpURLConnection") ||
                className.contains("NetworkInterface") ||
                (className.contains("Channel") && !className.contains("FileChannel")) ||
                className.contains("Selector") ||
                className.contains("SocketChannel") ||
                className.contains("DatagramChannel") ||
                className.contains("NettyClient") ||
                className.contains("NettyServer") ||
                className.contains("WebClient") ||
                className.contains("RestTemplate") ||
                methodName.contains("connect") ||
                methodName.contains("accept") ||
                (methodName.contains("read") && className.contains("Socket")) ||
                (methodName.contains("write") && className.contains("Socket")) ||
                methodName.contains("send") ||
                methodName.contains("receive");
    }

    public static boolean isLogType(SootField sootField) {
        if (sootField == null) {
            return false;
        }

        String typeName = sootField.getType().toString();


        if (typeName.equals("org.slf4j.Logger") ||
                typeName.equals("java.util.logging.Logger") ||
                typeName.equals("org.apache.log4j.Logger") ||
                typeName.equals("ch.qos.logback.classic.Logger") ||
                typeName.equals("org.apache.commons.logging.Log")) {
            return true;
        }

        if (typeName.equals("Logger") || typeName.equals("Log")) {
            return true;
        }

        if (sootField.getType() instanceof RefType) {
            RefType refType = (RefType) sootField.getType();
            SootClass sootClass = refType.getSootClass();
            String className = sootClass.getName();


            return className.equals("org.slf4j.Logger") ||
                    className.equals("java.util.logging.Logger") ||
                    className.equals("org.apache.log4j.Logger") ||
                    className.equals("ch.qos.logback.classic.Logger") ||
                    className.equals("org.apache.commons.logging.Log");
        }

        return false;
    }

    public static boolean isImmutableCollection(SootField sootField) {
        if (sootField == null) {
            return false;
        }

        String typeName = sootField.getType().toString();

        
        if (isJavaUtilImmutableCollection(typeName)) {
            return true;
        }

        
        if (isGuavaImmutableCollection(typeName)) {
            return true;
        }

        
        if (sootField.getType() instanceof RefType) {
            RefType refType = (RefType) sootField.getType();
            SootClass sootClass = refType.getSootClass();
            String className = sootClass.getName();

            return isJavaUtilImmutableCollection(className) ||
                    isGuavaImmutableCollection(className);
        }

        return false;
    }

    private static boolean isJavaUtilImmutableCollection(String typeName) {
        
        if (typeName.startsWith("java.util.Collections$")) {
            String innerClass = typeName.substring("java.util.Collections$".length());
            return innerClass.equals("EmptyList") ||
                    innerClass.equals("EmptySet") ||
                    innerClass.equals("EmptyMap") ||
                    innerClass.equals("SingletonList") ||
                    innerClass.equals("SingletonSet") ||
                    innerClass.equals("SingletonMap") ||
                    innerClass.equals("UnmodifiableCollection") ||
                    innerClass.equals("UnmodifiableSet") ||
                    innerClass.equals("UnmodifiableList") ||
                    innerClass.equals("UnmodifiableMap") ||
                    innerClass.equals("UnmodifiableSortedSet") ||
                    innerClass.equals("UnmodifiableSortedMap") ||
                    innerClass.equals("UnmodifiableNavigableSet") ||
                    innerClass.equals("UnmodifiableNavigableMap") ||
                    innerClass.equals("UnmodifiableRandomAccessList") ||
                    innerClass.equals("SynchronizedCollection") ||
                    innerClass.equals("SynchronizedSet") ||
                    innerClass.equals("SynchronizedList") ||
                    innerClass.equals("SynchronizedMap") ||
                    innerClass.equals("SynchronizedSortedSet") ||
                    innerClass.equals("SynchronizedSortedMap") ||
                    innerClass.equals("SynchronizedNavigableSet") ||
                    innerClass.equals("SynchronizedNavigableMap") ||
                    innerClass.equals("SynchronizedRandomAccessList") ||
                    innerClass.equals("CheckedCollection") ||
                    innerClass.equals("CheckedSet") ||
                    innerClass.equals("CheckedList") ||
                    innerClass.equals("CheckedMap") ||
                    innerClass.equals("CheckedSortedSet") ||
                    innerClass.equals("CheckedSortedMap") ||
                    innerClass.equals("CheckedNavigableSet") ||
                    innerClass.equals("CheckedNavigableMap") ||
                    innerClass.equals("CheckedRandomAccessList");
        }

        
        if (typeName.startsWith("java.util.ImmutableCollections$")) {
            return true;
        }

        
        if (typeName.equals("java.util.Arrays$ArrayList")) {
            return true;
        }

        return false;
    }

    private static boolean isGuavaImmutableCollection(String typeName) {
        
        if (typeName.startsWith("com.google.common.collect.Immutable")) {
            return true;
        }

        
        if (typeName.equals("com.google.common.collect.ImmutableList") ||
                typeName.equals("com.google.common.collect.ImmutableSet") ||
                typeName.equals("com.google.common.collect.ImmutableMap") ||
                typeName.equals("com.google.common.collect.ImmutableMultiset") ||
                typeName.equals("com.google.common.collect.ImmutableMultimap") ||
                typeName.equals("com.google.common.collect.ImmutableBiMap") ||
                typeName.equals("com.google.common.collect.ImmutableSortedSet") ||
                typeName.equals("com.google.common.collect.ImmutableSortedMap") ||
                typeName.equals("com.google.common.collect.ImmutableSortedMultiset") ||
                typeName.equals("com.google.common.collect.ImmutableTable") ||
                typeName.equals("com.google.common.collect.ImmutableRangeSet") ||
                typeName.equals("com.google.common.collect.ImmutableRangeMap") ||
                typeName.equals("com.google.common.collect.ImmutableClassToInstanceMap")) {
            return true;
        }

        
        if (typeName.equals("ImmutableList") ||
                typeName.equals("ImmutableSet") ||
                typeName.equals("ImmutableMap") ||
                typeName.equals("ImmutableMultiset") ||
                typeName.equals("ImmutableMultimap") ||
                typeName.equals("ImmutableBiMap") ||
                typeName.equals("ImmutableSortedSet") ||
                typeName.equals("ImmutableSortedMap") ||
                typeName.equals("ImmutableSortedMultiset") ||
                typeName.equals("ImmutableTable") ||
                typeName.equals("ImmutableRangeSet") ||
                typeName.equals("ImmutableRangeMap") ||
                typeName.equals("ImmutableClassToInstanceMap")) {
            return true;
        }

        return false;
    }


}

