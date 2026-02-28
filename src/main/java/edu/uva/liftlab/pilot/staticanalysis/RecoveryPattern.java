package edu.uva.liftlab.pilot.staticanalysis;

import soot.SootMethod;
import soot.Trap;
import soot.Unit;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class RecoveryPattern {
    private RecoveryPatternType type = RecoveryPatternType.UNKNOWN;
    private SootMethod method;
    private Trap trap;
    private Set<SootMethod> involvedMethods = new HashSet<>();
    private Set<Unit> recoveryUnits = new HashSet<>();
    private Map<SootMethod, Unit> callSites = new HashMap<>();

    public void addInvolvedMethod(SootMethod method) {
        involvedMethods.add(method);
    }

    public void addMethodCallSite(SootMethod method, Unit callSite) {
        callSites.put(method, callSite);
    }

    public void setType(RecoveryPatternType type) {
        this.type = type;
    }

    public void setMethod(SootMethod method) {
        this.method = method;
    }

    public void setTrap(Trap trap) {
        this.trap = trap;
    }

    public void setRecoveryUnits(Set<Unit> units) {
        this.recoveryUnits = units;
    }

    public RecoveryPatternType getType() {
        return type;
    }

    public SootMethod getMethod() {
        return method;
    }

    public Trap getTrap() {
        return trap;
    }

    public Set<SootMethod> getInvolvedMethods() {
        return involvedMethods;
    }

    public Set<Unit> getRecoveryUnits() {
        return recoveryUnits;
    }

    public Map<SootMethod, Unit> getCallSites() {
        return callSites;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("RecoveryPattern[type=").append(type)
                .append(", method=").append(method.getSignature())
                .append(", exceptionType=").append(trap != null ? trap.getException().getName() : "N/A")
                .append(", involvedMethods=").append(involvedMethods.size())
                .append("]");
        return sb.toString();
    }
}

