package edu.uva.liftlab.pilot.isolation.IO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;
import edu.uva.liftlab.pilot.util.LocalGeneratorUtil;

import java.util.ArrayList;
import java.util.List;


class PathHandler extends BaseIOHandler {
    private static final Logger logger = LoggerFactory.getLogger(PathHandler.class);
    private static final String PATH_CLASS = "java.nio.file.Path";
    private static final String SHADOW_PATH_CLASS = "org.pilot.filesystem.ShadowPath";
    private static final String LINK_OPTION_CLASS = "java.nio.file.LinkOption";

    @Override
    public boolean handle(IOContext context) {
        InvokeExpr expr = context.getInvokeExpr();
        if (expr == null) {
            return false;
        }

        if (!isPathToRealPath(expr)) {
            return false;
        }

        return handleToRealPath(context, expr);
    }

    private boolean handleToRealPath(IOContext context, InvokeExpr expr) {
        logger.info("Handling Path.toRealPath() operation: {}", expr);

        Value pathInstance = getPathInstance(expr);
        if (pathInstance == null) {
            logger.error("Failed to get Path instance from expression");
            return false;
        }

        LocalGeneratorUtil lg = context.getLg();
        UnitPatchingChain units = context.getUnits();
        Unit currentUnit = context.getUnit();

        try {
            SootClass shadowPathClass = Scene.v().getSootClass(SHADOW_PATH_CLASS);

            List<Type> constructorParams = new ArrayList<>();
            constructorParams.add(RefType.v(PATH_CLASS));

            SootMethodRef constructorRef = Scene.v().makeMethodRef(
                    shadowPathClass,
                    "<init>",
                    constructorParams,
                    VoidType.v(),
                    false
            );

            Local shadowPathLocal = lg.generateLocal(RefType.v(SHADOW_PATH_CLASS));

            NewExpr newExpr = Jimple.v().newNewExpr(RefType.v(SHADOW_PATH_CLASS));
            AssignStmt newStmt = Jimple.v().newAssignStmt(shadowPathLocal, newExpr);
            units.insertBefore(newStmt, currentUnit);


            List<Value> constructorArgs = new ArrayList<>();
            constructorArgs.add(pathInstance);

            SpecialInvokeExpr initExpr = Jimple.v().newSpecialInvokeExpr(
                    shadowPathLocal,
                    constructorRef,
                    constructorArgs
            );
            InvokeStmt initStmt = Jimple.v().newInvokeStmt(initExpr);
            units.insertBefore(initStmt, currentUnit);


            List<Value> originalArgs = expr.getArgs();


            List<Type> toRealPathParams = new ArrayList<>();

            if (!originalArgs.isEmpty()) {

                toRealPathParams.add(ArrayType.v(RefType.v(LINK_OPTION_CLASS), 1));
            }

            SootMethodRef toRealPathRef = Scene.v().makeMethodRef(
                    shadowPathClass,
                    "toRealPath",
                    toRealPathParams,
                    RefType.v(PATH_CLASS),
                    false
            );

            VirtualInvokeExpr shadowToRealPathExpr = Jimple.v().newVirtualInvokeExpr(
                    shadowPathLocal,
                    toRealPathRef,
                    originalArgs
            );

            if (currentUnit instanceof AssignStmt) {

                AssignStmt assignStmt = (AssignStmt) currentUnit;
                AssignStmt newAssignStmt = Jimple.v().newAssignStmt(
                        assignStmt.getLeftOp(),
                        shadowToRealPathExpr
                );
                units.swapWith(currentUnit, newAssignStmt);
            } else if (currentUnit instanceof InvokeStmt) {

                InvokeStmt newInvokeStmt = Jimple.v().newInvokeStmt(shadowToRealPathExpr);
                units.swapWith(currentUnit, newInvokeStmt);
            } else {
                logger.warn("Unexpected unit type: {}", currentUnit.getClass());
                return false;
            }

            logger.info("Successfully replaced Path.toRealPath() with new ShadowPath(path).toRealPath()");
            return true;

        } catch (Exception e) {
            logger.error("Failed to handle Path.toRealPath(): {}", e.getMessage(), e);
            return false;
        }
    }


    private boolean isPathToRealPath(InvokeExpr expr) {
        if (!(expr instanceof InstanceInvokeExpr)) {
            return false;
        }

        SootMethod method = expr.getMethod();


        if (!method.getName().equals("toRealPath")) {
            return false;
        }


        SootClass declaringClass = method.getDeclaringClass();


        if (declaringClass.getName().equals(PATH_CLASS)) {
            return true;
        }


        for (SootClass iface : declaringClass.getInterfaces()) {
            if (iface.getName().equals(PATH_CLASS)) {
                return true;
            }
        }


        if (declaringClass.hasSuperclass()) {
            return isImplementingPath(declaringClass.getSuperclass());
        }

        return false;
    }

    private boolean isImplementingPath(SootClass clazz) {
        if (clazz == null) {
            return false;
        }


        for (SootClass iface : clazz.getInterfaces()) {
            if (iface.getName().equals(PATH_CLASS)) {
                return true;
            }
        }


        if (clazz.hasSuperclass()) {
            return isImplementingPath(clazz.getSuperclass());
        }

        return false;
    }


    private Value getPathInstance(InvokeExpr expr) {
        if (expr instanceof InstanceInvokeExpr) {
            return ((InstanceInvokeExpr) expr).getBase();
        }
        return null;
    }
}