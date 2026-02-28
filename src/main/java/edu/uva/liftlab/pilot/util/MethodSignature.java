package edu.uva.liftlab.pilot.util;


import soot.ArrayType;
import soot.SootMethod;
import soot.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


public class MethodSignature {
    private final String name;
    private final List<String> paramTypes;
    private final String returnType;


    public MethodSignature(String name, List<String> paramTypes, String returnType) {
        this.name = name;
        this.paramTypes = new ArrayList<>(paramTypes);
        this.returnType = returnType;
    }


    public static MethodSignature fromSootMethod(SootMethod method) {
        List<String> paramTypes = new ArrayList<>();
        for (Type paramType : method.getParameterTypes()) {
            paramTypes.add(normalizeTypeName(paramType));
        }

        String returnType = normalizeTypeName(method.getReturnType());

        return new MethodSignature(method.getName(), paramTypes, returnType);
    }


    private static String normalizeTypeName(Type type) {
        String typeName = type.toString();

        
        if (type instanceof ArrayType) {
            ArrayType arrayType = (ArrayType) type;
            return normalizeTypeName(arrayType.getElementType()) + "[]";
        }

        
        int genericIndex = typeName.indexOf('<');
        if (genericIndex != -1) {
            typeName = typeName.substring(0, genericIndex);
        }

        return typeName;
    }


    public String toSimpleString() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("(");
        for (int i = 0; i < paramTypes.size(); i++) {
            if (i > 0) sb.append(", ");
            
            String param = paramTypes.get(i);
            int lastDot = param.lastIndexOf('.');
            if (lastDot != -1) {
                param = param.substring(lastDot + 1);
            }
            sb.append(param);
        }
        sb.append(")");
        return sb.toString();
    }

    // Getters
    public String getName() {
        return name;
    }

    public List<String> getParamTypes() {
        return new ArrayList<>(paramTypes);
    }

    public String getReturnType() {
        return returnType;
    }

    public int getParameterCount() {
        return paramTypes.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MethodSignature that = (MethodSignature) o;
        return Objects.equals(name, that.name) &&
                Objects.equals(paramTypes, that.paramTypes) &&
                Objects.equals(returnType, that.returnType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, paramTypes, returnType);
    }

    @Override
    public String toString() {
        return returnType + " " + name + "(" + String.join(", ", paramTypes) + ")";
    }
}
