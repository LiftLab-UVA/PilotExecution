package edu.uva.liftlab.pilot.util;

import soot.*;
import soot.jimple.Jimple;

public class LocalGeneratorUtil {
    private final Body body;

    public LocalGeneratorUtil(Body body) {
        this.body = body;
    }

    public Local generateLocal(Type type) {

        int count = body.getLocalCount();
        String name;
        Local local;

        do {
            name = "local" + count++;
            local = Jimple.v().newLocal(name, type);
        } while (body.getLocals().contains(local));

        body.getLocals().add(local);
        return local;
    }

    public Local generateLocalWithId(Type type, String id) {

        int count = body.getLocalCount();
        String name;
        Local local;

        do {
            name = "local" + id + count++;
            local = Jimple.v().newLocal(name, type);
        } while (body.getLocals().contains(local));

        body.getLocals().add(local);
        return local;
    }

    public String getClassName() {
        return body.getMethod().getDeclaringClass().getName();
    }


}