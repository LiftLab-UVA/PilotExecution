package edu.uva.liftlab.pilot.isolation.IO;

import edu.uva.liftlab.pilot.util.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;

import java.util.*;


class FileHandler extends BaseIOHandler {
    private static final Logger logger = LoggerFactory.getLogger(FileHandler.class);
    private static final String FILES_CLASS = "java.nio.file.Files";
    private static final String SHADOW_FILES = "org.pilot.filesystem.ShadowFiles";

    private static final Set<MethodSignature> HANDLED_METHOD_SIGNATURES = new HashSet<>();

    static {
        initializeHandledMethods();
    }


    private static void initializeHandledMethods() {
        // move(Path source, Path target, CopyOption... options)
        HANDLED_METHOD_SIGNATURES.add(new MethodSignature(
                "move",
                Arrays.asList("java.nio.file.Path", "java.nio.file.Path", "java.nio.file.CopyOption[]"),
                "java.nio.file.Path"
        ));

        // isDirectory(Path path)
        HANDLED_METHOD_SIGNATURES.add(new MethodSignature(
                "isDirectory",
                Arrays.asList("java.nio.file.Path"),
                "boolean"
        ));

        // createDirectories(Path dir, FileAttribute<?>... attrs)
        HANDLED_METHOD_SIGNATURES.add(new MethodSignature(
                "createDirectories",
                Arrays.asList("java.nio.file.Path", "java.nio.file.attribute.FileAttribute[]"),
                "java.nio.file.Path"
        ));

        // newDirectoryStream(Path dir)
        HANDLED_METHOD_SIGNATURES.add(new MethodSignature(
                "newDirectoryStream",
                Arrays.asList("java.nio.file.Path"),
                "java.nio.file.DirectoryStream"
        ));

        // size(Path path)
        HANDLED_METHOD_SIGNATURES.add(new MethodSignature(
                "size",
                Arrays.asList("java.nio.file.Path"),
                "long"
        ));

        // delete(Path path)
        HANDLED_METHOD_SIGNATURES.add(new MethodSignature(
                "delete",
                Arrays.asList("java.nio.file.Path"),
                "void"
        ));

        // newByteChannel(Path path, OpenOption... options)
        HANDLED_METHOD_SIGNATURES.add(new MethodSignature(
                "newByteChannel",
                Arrays.asList("java.nio.file.Path", "java.nio.file.OpenOption[]"),
                "java.nio.channels.SeekableByteChannel"
        ));

        // deleteIfExists(Path path)
        HANDLED_METHOD_SIGNATURES.add(new MethodSignature(
                "deleteIfExists",
                Arrays.asList("java.nio.file.Path"),
                "boolean"
        ));

        // createFile(Path path, FileAttribute<?>... attrs)
        HANDLED_METHOD_SIGNATURES.add(new MethodSignature(
                "createFile",
                Arrays.asList("java.nio.file.Path", "java.nio.file.attribute.FileAttribute[]"),
                "java.nio.file.Path"
        ));

        // walkFileTree(Path start, FileVisitor<? super Path> visitor)
        HANDLED_METHOD_SIGNATURES.add(new MethodSignature(
                "walkFileTree",
                Arrays.asList("java.nio.file.Path", "java.nio.file.FileVisitor"),
                "java.nio.file.Path"
        ));

        // exists(Path path, LinkOption... options)
        HANDLED_METHOD_SIGNATURES.add(new MethodSignature(
                "exists",
                Arrays.asList("java.nio.file.Path", "java.nio.file.LinkOption[]"),
                "boolean"
        ));

        // newInputStream(Path path, OpenOption... options)
        HANDLED_METHOD_SIGNATURES.add(new MethodSignature(
                "newInputStream",
                Arrays.asList("java.nio.file.Path", "java.nio.file.OpenOption[]"),
                "java.io.InputStream"
        ));

        // newOutputStream(Path path, OpenOption... options)
        HANDLED_METHOD_SIGNATURES.add(new MethodSignature(
                "newOutputStream",
                Arrays.asList("java.nio.file.Path", "java.nio.file.OpenOption[]"),
                "java.io.OutputStream"
        ));
    }

    @Override
    public boolean handle(IOContext context) {
        InvokeExpr expr = context.getInvokeExpr();
        if (expr == null) {
            return false;
        }

        if (!isFilesStaticCall(expr)) {
            return false;
        }

        SootMethod method = expr.getMethod();

        MethodSignature signature = MethodSignature.fromSootMethod(method);

        if (!HANDLED_METHOD_SIGNATURES.contains(signature)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Method Files.{} with signature {} is not in the handled list, skipping",
                        method.getName(), signature.toSimpleString());
            }
            return false;
        }

        return redirectToShadowFiles(context, expr, method);
    }

    private boolean redirectToShadowFiles(IOContext context, InvokeExpr expr, SootMethod originalMethod) {
        String methodName = originalMethod.getName();

        logger.info("Redirecting Files.{} to ShadowFiles.{}", methodName, methodName);

        try {

            SootClass shadowFilesClass = Scene.v().getSootClass(SHADOW_FILES);

            SootMethodRef shadowMethodRef = Scene.v().makeMethodRef(
                    shadowFilesClass,
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

            logger.info("Successfully redirected Files.{} to ShadowFiles.{} with {} arguments",
                    methodName, methodName, expr.getArgCount());
            return true;

        } catch (Exception e) {
            logger.error("Failed to redirect Files.{} to ShadowFiles.{}: {}",
                    methodName, methodName, e.getMessage());
            return false;
        }
    }

    private boolean isFilesStaticCall(InvokeExpr expr) {
        if (!(expr instanceof StaticInvokeExpr)) {
            return false;
        }

        SootMethod method = expr.getMethod();
        String declaringClass = method.getDeclaringClass().getName();

        return declaringClass.equals(FILES_CLASS);
    }

    private void logMethodSignature(SootMethod method) {
        if (logger.isDebugEnabled()) {
            MethodSignature signature = MethodSignature.fromSootMethod(method);
            logger.debug("Method signature: {}", signature);
        }
    }
}