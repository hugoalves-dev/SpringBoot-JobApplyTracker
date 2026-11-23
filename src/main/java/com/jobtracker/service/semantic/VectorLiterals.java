package com.jobtracker.service.semantic;

/** pgvector text format: {@code [0.1,0.2,...]}, passed as a parameter and cast with {@code CAST(:v AS vector)}. */
public final class VectorLiterals {
    private VectorLiterals() {}

    public static String toLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
