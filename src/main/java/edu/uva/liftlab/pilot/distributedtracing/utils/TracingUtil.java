package edu.uva.liftlab.pilot.distributedtracing.utils;

import soot.RefType;
import soot.SootClass;
import soot.SootMethod;
import soot.Type;

import static edu.uva.liftlab.pilot.distributedtracing.ExecutorPropagator.executorServiceTypes;

public class TracingUtil {

    public static boolean isFutureTaskType(Type type){
        if (!(type instanceof RefType)) {
            return false;
        }
        SootClass cls = ((RefType) type).getSootClass();
        return isFutureTask(cls);

    }
    public static boolean isRunnableType(Type type) {
        if (!(type instanceof RefType)) {
            return false;
        }
        SootClass cls = ((RefType) type).getSootClass();
        return implementsRunnable(cls) || isFutureTask(cls) ;
    }

    public static boolean isCallableType(Type type) {
        if (!(type instanceof RefType)) {
            return false;
        }
        SootClass cls = ((RefType) type).getSootClass();
        return implementsCallable(cls);
    }

    public static boolean isFutureTask(SootClass cls) {
        if (cls.getName().contains("java.util.concurrent.FutureTask")) {
            return true;
        }

        for (SootClass iface : cls.getInterfaces()) {
            if (isFutureTask(iface)) {
                return true;
            }
        }

        if (cls.hasSuperclass()) {
            return isFutureTask(cls.getSuperclass());
        }
        return false;
    }

    public static boolean implementsRunnable(SootClass cls) {
        if (isFutureTask(cls)) {
            return true;
        }
        if (cls.getName().equals("java.lang.Runnable")) {
            return true;
        }

        for (SootClass iface : cls.getInterfaces()) {
            if (implementsRunnable(iface)) {
                return true;
            }
        }

        if (cls.hasSuperclass()) {
            return implementsRunnable(cls.getSuperclass());
        }

        return false;
    }

    public static boolean implementsCallable(SootClass cls) {
        if (cls.getName().equals("java.util.concurrent.Callable")) {
            return true;
        }

        for (SootClass iface : cls.getInterfaces()) {
            if (implementsCallable(iface)) {
                return true;
            }
        }

        if (cls.hasSuperclass()) {
            return implementsCallable(cls.getSuperclass());
        }

        return false;
    }


    public static boolean isScheduledExecutorService(SootClass cls) {
        if (cls.getName().contains("java.util.concurrent.ScheduledExecutorService")) {
            return true;
        }

        for (SootClass iface : cls.getInterfaces()) {
            if (isScheduledExecutorService(iface)) {
                return true;
            }
        }
//
        if (cls.hasSuperclass()) {
            return isScheduledExecutorService(cls.getSuperclass());
        }

        return false;
    }

    public static boolean isExecutor(SootClass cls) {
        if (cls.getName().contains("java.util.concurrent.Executor")) {
            return true;
        }

        for (SootClass iface : cls.getInterfaces()) {
            if (isExecutor(iface)) {
                return true;
            }
        }

        if (cls.hasSuperclass()) {
            return isExecutor(cls.getSuperclass());
        }

        return false;
    }

    public static boolean isExecutorService(SootClass cls) {
        for (String type : executorServiceTypes) {
            if (cls.getName().contains(type)) {
                return true;
            }
        }

        for (SootClass iface : cls.getInterfaces()) {
            if (isExecutorService(iface)) {
                return true;
            }
        }

        if (cls.hasSuperclass()) {
            return isExecutorService(cls.getSuperclass());
        }

        return false;
    }

    public static boolean isExecutorMethod(SootMethod method) {
        String name = method.getName();
        return name.contains("execute") ||
                name.contains("submit") ||
                name.contains("schedule") ||
                name.contains("invokeAll") ||
                name.contains("invokeAny");
    }

    public static boolean isExecutorType(Type type) {
        if (!(type instanceof RefType)) {
            return false;
        }
        SootClass sc = ((RefType) type).getSootClass();
        return isExecutor(sc) || isExecutorService(sc) || isScheduledExecutorService(sc);
    }
}
