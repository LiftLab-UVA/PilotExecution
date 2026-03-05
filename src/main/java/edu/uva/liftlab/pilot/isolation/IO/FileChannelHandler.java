package edu.uva.liftlab.pilot.isolation.IO;

import edu.uva.liftlab.pilot.util.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;

import java.util.*;


class FileChannelHandler extends BaseIOHandler {
    private static final Logger logger = LoggerFactory.getLogger(FileChannelHandler.class);
    private static final String FILE_CHANNEL_CLASS = "java.nio.channels.FileChannel";
    private static final String SHADOW_FILE_CHANNEL = "org.pilot.filesystem.ShadowFileChannel";

    private static final Set<MethodSignature> HANDLED_METHOD_SIGNATURES = new HashSet<>();

    static {
        initializeHandledMethods();
    }

    private static void initializeHandledMethods() {
        // open(Path path, OpenOption... options)
        HANDLED_METHOD_SIGNATURES.add(new MethodSignature(
                "open",
                Arrays.asList("java.nio.file.Path", "java.nio.file.OpenOption[]"),
                "java.nio.channels.FileChannel"
        ));

    }

    @Override
    public boolean handle(IOContext context) {
        InvokeExpr expr = context.getInvokeExpr();
        if (expr == null) {
            return false;
        }

        if (!isFileChannelStaticCall(expr)) {
            return false;
        }

        SootMethod method = expr.getMethod();


        MethodSignature signature = MethodSignature.fromSootMethod(method);

        if (!HANDLED_METHOD_SIGNATURES.contains(signature)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Method FileChannel.{} with signature {} is not in the handled list, skipping",
                        method.getName(), signature.toSimpleString());
            }
            return false;
        }

        return redirectToShadowFileChannel(context, expr, method);
    }

    private boolean redirectToShadowFileChannel(IOContext context, InvokeExpr expr, SootMethod originalMethod) {
        String methodName = originalMethod.getName();

        logger.info("Redirecting FileChannel.{} to ShadowFileChannel.{}", methodName, methodName);

        try {

            SootClass shadowFileChannelClass = Scene.v().getSootClass(SHADOW_FILE_CHANNEL);

            SootMethodRef shadowMethodRef = Scene.v().makeMethodRef(
                    shadowFileChannelClass,
                    methodName,
                    originalMethod.getParameterTypes(),
                    originalMethod.getReturnType(),
                    true  // static method
            );


            StaticInvokeExpr newExpr = Jimple.v().newStaticInvokeExpr(
                    shadowMethodRef,
                    expr.getArgs()
            );


            Unit currentUnit = context.getUnit();
            UnitPatchingChain units = context.getUnits();

            if (currentUnit instanceof AssignStmt) {

                AssignStmt assignStmt = (AssignStmt) currentUnit;
                AssignStmt newStmt = Jimple.v().newAssignStmt(
                        assignStmt.getLeftOp(),
                        newExpr
                );
                units.swapWith(currentUnit, newStmt);
            } else if (currentUnit instanceof InvokeStmt) {
                InvokeStmt newStmt = Jimple.v().newInvokeStmt(newExpr);
                units.swapWith(currentUnit, newStmt);
            } else {
                logger.warn("Unexpected unit type: {}", currentUnit.getClass());
                return false;
            }

            logger.info("Successfully redirected FileChannel.{} to ShadowFileChannel.{} with {} arguments",
                    methodName, methodName, expr.getArgCount());
            return true;

        } catch (Exception e) {
            logger.error("Failed to redirect FileChannel.{} to ShadowFileChannel.{}: {}",
                    methodName, methodName, e.getMessage());
            return false;
        }
    }


    private boolean isFileChannelStaticCall(InvokeExpr expr) {
        if (!(expr instanceof StaticInvokeExpr)) {
            return false;
        }

        SootMethod method = expr.getMethod();
        String declaringClass = method.getDeclaringClass().getName();

        return declaringClass.equals(FILE_CHANNEL_CLASS);
    }


    private void logMethodSignature(SootMethod method) {
        if (logger.isDebugEnabled()) {
            MethodSignature signature = MethodSignature.fromSootMethod(method);
            logger.debug("Method signature: {}", signature);
        }
    }
}