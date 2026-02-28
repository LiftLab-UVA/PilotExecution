package edu.uva.liftlab.pilot.staticanalysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;
import soot.toolkits.graph.BriefUnitGraph;
import soot.toolkits.graph.UnitGraph;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles analysis of exception handlers (traps)
 */
public class TrapAnalyzer {
    private static final Logger LOG = LoggerFactory.getLogger(TrapAnalyzer.class);


    public Set<Unit> identifyCatchBlockUnits(Unit handlerUnit, Body body, UnitGraph cfg) {
        // Get the trap that has this handler
        Trap catchTrap = body.getTraps().stream()
                .filter(trap -> trap.getHandlerUnit().equals(handlerUnit))
                .findFirst()
                .orElse(null);

        if (catchTrap == null) {
            return Collections.emptySet();
        }

        // Collect all units reachable from normal method entry
        Set<Unit> reachableFromNormalFlow = new HashSet<>();
        Unit entryPoint = body.getUnits().getFirst();
        collectReachableUnits(entryPoint, reachableFromNormalFlow, cfg, body.getTraps().stream()
                .map(Trap::getHandlerUnit)
                .collect(Collectors.toSet()));

        // Collect all units reachable from OTHER exception handlers
        // (not the one we're analyzing)
        Set<Unit> reachableFromOtherHandlers = new HashSet<>();
        for (Trap trap : body.getTraps()) {
            Unit otherHandler = trap.getHandlerUnit();
            // Skip the handler we're analyzing
            if (otherHandler.equals(handlerUnit)) {
                continue;
            }
            // Collect units reachable from this other handler
            collectReachableUnits(otherHandler, reachableFromOtherHandlers, cfg, Collections.emptySet());
        }

        // Combine units reachable from normal flow and other handlers
        // These are units we want to exclude from our catch block
        Set<Unit> exclusionUnits = new HashSet<>();
        exclusionUnits.addAll(reachableFromNormalFlow);
        exclusionUnits.addAll(reachableFromOtherHandlers);

        // Now collect all units reachable from THIS catch handler
        // that are NOT in the exclusion set
        Set<Unit> catchBlockUnits = new HashSet<>();
        Queue<Unit> workQueue = new LinkedList<>();
        Set<Unit> visited = new HashSet<>();

        workQueue.add(handlerUnit);
        visited.add(handlerUnit);
        catchBlockUnits.add(handlerUnit);

        while (!workQueue.isEmpty()) {
            Unit current = workQueue.poll();

            for (Unit succ : cfg.getSuccsOf(current)) {
                // Skip if already visited
                if (visited.contains(succ)) {
                    continue;
                }

                visited.add(succ);

                // If NOT in exclusion set, it's part of THIS catch block
                if (!exclusionUnits.contains(succ)) {
                    catchBlockUnits.add(succ);
                    workQueue.add(succ);
                }
            }
        }

        return catchBlockUnits;
    }

    /**
     * Collects all units reachable from a starting point in the control flow graph,
     * excluding specified handler units.
     */
    private void collectReachableUnits(Unit startUnit, Set<Unit> reachable, UnitGraph cfg,
                                       Set<Unit> excludedHandlers) {
        Queue<Unit> queue = new LinkedList<>();
        queue.add(startUnit);
        reachable.add(startUnit);

        while (!queue.isEmpty()) {
            Unit current = queue.poll();

            for (Unit succ : cfg.getSuccsOf(current)) {
                // Skip excluded handler units
                if (excludedHandlers.contains(succ)) {
                    continue;
                }

                if (!reachable.contains(succ)) {
                    reachable.add(succ);
                    queue.add(succ);
                }
            }
        }
    }

    private boolean isCommonExitPoint(Unit unit, Body body) {
        int gotoCount = 0;
        for (Unit u : body.getUnits()) {
            if (u instanceof GotoStmt && ((GotoStmt) u).getTarget().equals(unit)) {
                gotoCount++;
            }
        }
        return gotoCount > 1;
    }

    /**
     * Checks if a unit is dominated by another unit in the control flow graph.
     * A unit X dominates unit Y if every path from the entry point to Y must go through X.
     */
    private boolean isDominatedBy(Unit unit, Unit dominator, Body body, UnitGraph cfg, Trap catchTrap) {
        if (unit.equals(dominator)) {
            return true;
        }

        // Start from entry points
        Set<Unit> entryPoints = findMethodEntryPoints(body, cfg, catchTrap);

        // For each entry point, check if there's a path to the unit that doesn't go through dominator
        for (Unit entry : entryPoints) {
            if (entry.equals(dominator)) {
                continue;
            }

            if (isReachableWithout(entry, unit, dominator, cfg, new HashSet<>())) {
                return false;
            }
        }

        return true;
    }

    private boolean isUnitBetween(Unit unit, Unit start, Unit end, Body body) {
        boolean afterStart = false;

        for (Unit u : body.getUnits()) {
            if (u.equals(start)) {
                afterStart = true;
            }

            if (afterStart && u.equals(unit)) {
                return true;
            }

            if (u.equals(end)) {
                afterStart = false;
            }
        }

        return false;
    }

    /**
     * Determines if a unit is in a finally block.
     * This is approximated by checking if the unit is reachable from multiple handlers
     * or from both normal flow and exception handlers.
     */
    private boolean isInFinallyBlock(Unit unit, Body body, UnitGraph cfg) {
        // A unit is likely in a finally block if:

        // 1. It's reachable from multiple exception handlers
        Set<Unit> handlerUnits = body.getTraps().stream()
                .map(Trap::getHandlerUnit)
                .collect(Collectors.toSet());

        int reachableFromHandlers = 0;
        for (Unit handler : handlerUnits) {
            if (isReachableFrom(handler, unit, cfg, new HashSet<>())) {
                reachableFromHandlers++;
            }
        }

        if (reachableFromHandlers > 1) {
            return true;
        }

        // 2. It's reachable from both a try block and its corresponding catch block
        for (Trap trap : body.getTraps()) {
            Unit beginUnit = trap.getBeginUnit();
            Unit handlerUnit = trap.getHandlerUnit();

            boolean reachableFromTry = isReachableFrom(beginUnit, unit, cfg, Collections.singleton(handlerUnit));
            boolean reachableFromCatch = isReachableFrom(handlerUnit, unit, cfg, new HashSet<>());

            if (reachableFromTry && reachableFromCatch) {
                return true;
            }
        }

        // 3. Additional check: many targets of different goto statements
        int targetsCount = 0;
        for (Unit u : body.getUnits()) {
            if (u instanceof GotoStmt && ((GotoStmt) u).getTarget().equals(unit)) {
                targetsCount++;
            }
        }

        return targetsCount > 2; // If it's a target of multiple goto statements, likely in finally
    }

    public boolean isReachableFrom(Unit source, Unit target, UnitGraph cfg, Set<Unit> excluded) {
        if (source.equals(target)) {
            return true;
        }

        Set<Unit> visited = new HashSet<>(excluded);
        Queue<Unit> queue = new LinkedList<>();

        queue.add(source);
        visited.add(source);

        while (!queue.isEmpty()) {
            Unit current = queue.poll();

            for (Unit succ : cfg.getSuccsOf(current)) {
                if (succ.equals(target)) {
                    return true;
                }

                if (!visited.contains(succ)) {
                    visited.add(succ);
                    queue.add(succ);
                }
            }
        }

        return false;
    }


    private List<Unit> findPotentialCatchEnds(Unit handlerUnit, Body body, UnitGraph cfg) {
        List<Unit> potentialEnds = new ArrayList<>();
        Set<Unit> visited = new HashSet<>();
        Queue<Unit> workQueue = new LinkedList<>();

        workQueue.add(handlerUnit);
        visited.add(handlerUnit);

        while (!workQueue.isEmpty()) {
            Unit current = workQueue.poll();

            // Check for return statements
            if (current instanceof ReturnStmt || current instanceof ReturnVoidStmt) {
                potentialEnds.add(current);
                continue;
            }

            // Check for goto statements that might mark the end of catch block
            if (current instanceof GotoStmt) {
                GotoStmt gotoStmt = (GotoStmt) current;
                Unit target = gotoStmt.getTarget();

                // If the target is potentially in a finally block or outside the catch
                if (isInFinallyBlock(target, body, cfg) ||
                        isCommonExitPoint(target, body) ||
                        !isDominatedBy(target, handlerUnit, body, cfg, null)) {
                    potentialEnds.add(current);
                    continue;
                }
            }

            // Add successors to process
            for (Unit succ : cfg.getSuccsOf(current)) {
                if (!visited.contains(succ)) {
                    visited.add(succ);
                    workQueue.add(succ);
                }
            }
        }

        return potentialEnds;
    }


    private Set<Unit> getAllHandlerUnits(Body body) {
        return body.getTraps().stream()
                .map(Trap::getHandlerUnit)
                .collect(Collectors.toSet());
    }

    private boolean isReachableWithout(Unit source, Unit target, Unit excluded,
                                       UnitGraph cfg, Set<Unit> visited) {
        if (source.equals(excluded)) {
            return false;
        }

        if (source.equals(target)) {
            return true;
        }

        visited.add(source);

        for (Unit succ : cfg.getSuccsOf(source)) {
            if (!visited.contains(succ) &&
                    isReachableWithout(succ, target, excluded, cfg, visited)) {
                return true;
            }
        }

        return false;
    }

    private Set<Unit> findMethodEntryPoints(Body body, UnitGraph cfg, Trap catchTrap) {
        Set<Unit> entryPoints = new HashSet<>();

        // Add method entry point
        entryPoints.add(body.getUnits().getFirst());

        // Add all exception handlers
        for (Trap trap : body.getTraps()) {
            if(trap.equals(catchTrap)) continue; // Skip the current catch trap
            entryPoints.add(trap.getHandlerUnit());
        }

        return entryPoints;
    }
}