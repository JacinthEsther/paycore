package com.fintechplatform.paycore.common.persistence;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

public final class ConstraintViolations {

    private ConstraintViolations() {
    }

    public static String constraintName(DataIntegrityViolationException exception) {

        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }

        return null;
    }

    public static boolean violates(
            DataIntegrityViolationException exception,
            String constraint
    ) {
        return constraint.equalsIgnoreCase(constraintName(exception));
    }
}
