package edu.uva.liftlab.pilot.staticanalysis;

import soot.*;
import soot.jimple.*;
import soot.toolkits.graph.CompleteUnitGraph;
import soot.toolkits.scalar.ForwardFlowAnalysis;
import soot.util.Chain;

import java.util.*;

/**
 * PhantomThreadAnalyzer - Identifies threads that are suitable candidates for micro-fork
 * based on static analysis of their characteristics.
 *
 * A thread is considered suitable for phantom thread creation if it:
 * 1. Is long-running (contains loops or recursive calls)
 * 2. Accesses shared state (static fields or fields of objects passed between threads)
 * 3. Uses synchronization primitives (locks, wait/notify, etc.)
 * 4. Participates in recovery operations
 * 5. Has potential for fault propagation
 */
public class PhantomThreadAnalyzer {

    private SootClass threadClass;
    private Set<SootMethod> recoveryMethods;
    private Set<SootClass> candidateThreadClasses;
    private Map<SootMethod, ThreadCharacteristics> methodCharacteristics;

    /**
     * Constructor initializes the analyzer with known recovery methods
     * @param recoveryMethods Methods that are known to participate in recovery processes
     */
    public PhantomThreadAnalyzer(Set<SootMethod> recoveryMethods) {
        this.threadClass = Scene.v().getSootClass("java.lang.Thread");
        this.recoveryMethods = recoveryMethods;
        this.candidateThreadClasses = new HashSet<>();
        this.methodCharacteristics = new HashMap<>();
    }

    /**
     * Analyzes all classes in the application to find suitable candidates for phantom threads
     * @return Set of thread classes that are candidates for phantom thread creation
     */
    public Set<SootClass> analyzeCandidateThreads() {
        // Get all classes that extend Thread or implement Runnable
        identifyThreadClasses();

        // Analyze each candidate thread class
        for (SootClass threadClass : candidateThreadClasses) {
            analyzeThreadClass(threadClass);
        }

        // Filter candidates based on characteristics
        return filterSuitableCandidates();
    }

    /**
     * Identifies all classes that extend Thread or implement Runnable
     */
    private void identifyThreadClasses() {
        Chain<SootClass> classes = Scene.v().getApplicationClasses();

        for (SootClass clazz : classes) {
            // Check if class extends Thread
            if (clazz.hasSuperclass() && isThreadSubclass(clazz)) {
                candidateThreadClasses.add(clazz);
                continue;
            }

            // Check if class implements Runnable
//            for (SootClass interfaceClass : clazz.getInterfaces()) {
//                if (interfaceClass.getName().equals("java.lang.Runnable")) {
//                    candidateThreadClasses.add(clazz);
//                    break;
//                }
//            }
        }
    }

    /**
     * Recursively checks if a class is a subclass of Thread
     */
    private boolean isThreadSubclass(SootClass clazz) {
        if (!clazz.hasSuperclass()) {
            return false;
        }

        SootClass superClass = clazz.getSuperclass();
        if (superClass.equals(threadClass)) {
            return true;
        }

        return isThreadSubclass(superClass);
    }

    /**
     * Analyzes a thread class to determine its characteristics
     * @param threadClass The class to analyze
     */
    private void analyzeThreadClass(SootClass threadClass) {
        // Find the run method
        SootMethod runMethod = null;

        try {
            runMethod = threadClass.getMethodByName("run");
        } catch (RuntimeException e) {
            // Class might not have a run method
            return;
        }

        // Analyze the run method
        analyzeMethod(runMethod, new HashSet<>());
    }

    /**
     * Recursively analyzes a method and its callees
     * @param method The method to analyze
     * @param visitedMethods Set of methods already visited to prevent infinite recursion
     */
    private void analyzeMethod(SootMethod method, Set<SootMethod> visitedMethods) {
        if (visitedMethods.contains(method) || !method.hasActiveBody()) {
            return;
        }

        visitedMethods.add(method);

        // Create characteristics object for this method if it doesn't exist
        if (!methodCharacteristics.containsKey(method)) {
            methodCharacteristics.put(method, new ThreadCharacteristics());
        }

        ThreadCharacteristics characteristics = methodCharacteristics.get(method);

        // Create control flow graph
        Body body = method.getActiveBody();
        CompleteUnitGraph cfg = new CompleteUnitGraph(body);

        // Analyze loops
        LoopAnalysis loopAnalysis = new LoopAnalysis(cfg);
        if (loopAnalysis.hasLoops()) {
            characteristics.hasLoops = true;
        }

        // Analyze method body for other characteristics
        for (Unit unit : body.getUnits()) {
            analyzeUnit(unit, method, characteristics, visitedMethods);
        }

        // Check if this is a recovery method
        if (recoveryMethods.contains(method)) {
            characteristics.isRecoveryMethod = true;
        }
    }

    /**
     * Analyzes a single instruction unit
     */
    private void analyzeUnit(Unit unit, SootMethod method, ThreadCharacteristics characteristics,
                             Set<SootMethod> visitedMethods) {
        // Check for shared state access
        if (unit instanceof AssignStmt) {
            AssignStmt assignStmt = (AssignStmt) unit;
            Value leftOp = assignStmt.getLeftOp();
            Value rightOp = assignStmt.getRightOp();

            // Check for static field access
            if (leftOp instanceof StaticFieldRef || rightOp instanceof StaticFieldRef) {
                characteristics.accessesSharedState = true;
            }

            // Check for field references that might be shared
            if (leftOp instanceof InstanceFieldRef || rightOp instanceof InstanceFieldRef) {
                characteristics.accessesSharedState = true;
            }
        }

        // Check for synchronization primitives
        if (unit instanceof MonitorStmt) {
            characteristics.usesSynchronization = true;
        }

        // Check for wait/notify calls
        if (unit instanceof InvokeStmt) {
            InvokeStmt invokeStmt = (InvokeStmt) unit;
            if (isWaitOrNotifyCall(invokeStmt.getInvokeExpr())) {
                characteristics.usesSynchronization = true;
            }
        }

        // Check for method calls that might involve synchronization
        if (unit instanceof Stmt) {
            Stmt stmt = (Stmt) unit;
            if (stmt.containsInvokeExpr()) {
                InvokeExpr invokeExpr = stmt.getInvokeExpr();

                // Check if the call is a synchronization primitive
                if (isWaitOrNotifyCall(invokeExpr)) {
                    characteristics.usesSynchronization = true;
                }

                // Analyze called method recursively
                if (invokeExpr.getMethod().getDeclaringClass().isApplicationClass() &&
                        invokeExpr.getMethod().hasActiveBody()) {

                    SootMethod calledMethod = invokeExpr.getMethod();
                    analyzeMethod(calledMethod, visitedMethods);

                    // Inherit characteristics from called methods
                    if (methodCharacteristics.containsKey(calledMethod)) {
                        ThreadCharacteristics calledCharacteristics = methodCharacteristics.get(calledMethod);
                        characteristics.merge(calledCharacteristics);
                    }
                }
            }
        }
    }

    /**
     * Checks if a method call is a wait or notify operation
     */
    private boolean isWaitOrNotifyCall(InvokeExpr invokeExpr) {
        String methodName = invokeExpr.getMethod().getName();
        return methodName.equals("wait") ||
                methodName.equals("notify") ||
                methodName.equals("notifyAll") ||
                methodName.equals("lock") ||
                methodName.equals("unlock") ||
                methodName.equals("tryLock");
    }

    /**
     * Filters thread classes based on their characteristics to find suitable candidates
     * for phantom thread creation
     * @return Set of suitable thread classes
     */
    private Set<SootClass> filterSuitableCandidates() {
        Set<SootClass> suitableCandidates = new HashSet<>();

        for (SootClass threadClass : candidateThreadClasses) {
            try {
                SootMethod runMethod = threadClass.getMethodByName("run");
                if (methodCharacteristics.containsKey(runMethod)) {
                    ThreadCharacteristics characteristics = methodCharacteristics.get(runMethod);

                    // A thread is suitable if it:
                    // 1. Is long-running (has loops)
                    // 2. Accesses shared state OR uses synchronization primitives
                    // 3. Has potential risk for fault propagation (is involved in recovery or accesses shared state)
                    if (characteristics.hasLoops &&
                            (characteristics.accessesSharedState || characteristics.usesSynchronization || characteristics.isRecoveryMethod))
                    {
                        suitableCandidates.add(threadClass);
                    }
                }
            } catch (RuntimeException e) {
                // Class might not have a run method
            }
        }

        return suitableCandidates;
    }

    /**
     * Inner class for tracking thread characteristics
     */
    private static class ThreadCharacteristics {
        boolean hasLoops = false;
        boolean accessesSharedState = false;
        boolean usesSynchronization = false;
        boolean isRecoveryMethod = false;

        /**
         * Merges characteristics from another analysis
         */
        public void merge(ThreadCharacteristics other) {
            this.hasLoops = this.hasLoops || other.hasLoops;
            this.accessesSharedState = this.accessesSharedState || other.accessesSharedState;
            this.usesSynchronization = this.usesSynchronization || other.usesSynchronization;
            this.isRecoveryMethod = this.isRecoveryMethod || other.isRecoveryMethod;
        }
    }

    /**
     * Inner class for loop analysis
     */
    private static class LoopAnalysis extends ForwardFlowAnalysis<Unit, Set<Unit>> {
        private boolean hasLoops = false;

        public LoopAnalysis(CompleteUnitGraph graph) {
            super(graph);
            doAnalysis();
        }

        @Override
        protected void flowThrough(Set<Unit> in, Unit unit, Set<Unit> out) {
            out.clear();
            out.addAll(in);
            out.add(unit);

            // If this unit is already in the in-set, we have a loop
            if (in.contains(unit)) {
                hasLoops = true;
            }
        }

        @Override
        protected Set<Unit> newInitialFlow() {
            return new HashSet<>();
        }

        @Override
        protected void merge(Set<Unit> in1, Set<Unit> in2, Set<Unit> out) {
            out.clear();
            out.addAll(in1);
            out.addAll(in2);
        }

        @Override
        protected void copy(Set<Unit> source, Set<Unit> dest) {
            dest.clear();
            dest.addAll(source);
        }

        public boolean hasLoops() {
            return hasLoops;
        }
    }

    /**
     * Helper class for detailed thread analysis reporting
     */
    public static class ThreadAnalysisReport {
        private SootClass threadClass;
        private boolean isLongRunning;
        private boolean accessesSharedState;
        private boolean usesSynchronization;
        private boolean isRecoveryParticipant;
        private List<String> sharedStateAccesses = new ArrayList<>();
        private List<String> synchronizationPoints = new ArrayList<>();

        public ThreadAnalysisReport(SootClass threadClass) {
            this.threadClass = threadClass;
        }

        // Getters and setters for report fields

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Thread Class: ").append(threadClass.getName()).append("\n");
            sb.append("Long-running: ").append(isLongRunning).append("\n");
            sb.append("Accesses shared state: ").append(accessesSharedState).append("\n");
            sb.append("Uses synchronization: ").append(usesSynchronization).append("\n");
            sb.append("Participates in recovery: ").append(isRecoveryParticipant).append("\n");

            if (!sharedStateAccesses.isEmpty()) {
                sb.append("Shared state accesses:\n");
                for (String access : sharedStateAccesses) {
                    sb.append("  - ").append(access).append("\n");
                }
            }

            if (!synchronizationPoints.isEmpty()) {
                sb.append("Synchronization points:\n");
                for (String point : synchronizationPoints) {
                    sb.append("  - ").append(point).append("\n");
                }
            }

            return sb.toString();
        }
    }

    /**
     * Advanced method to generate detailed reports for each thread
     * @return List of detailed analysis reports
     */
    public List<ThreadAnalysisReport> generateDetailedReports() {
        List<ThreadAnalysisReport> reports = new ArrayList<>();

        // Implementation would create detailed reports for each thread
        // analyzing specific shared state accesses, synchronization points, etc.

        return reports;
    }
}