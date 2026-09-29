package com.fintechplatform.paycore.common.persistence;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Lets services rely on database constraints instead of "does it exist?"
 * queries before every insert: the insert either succeeds, or fails with
 * the name of the rule it broke. One round trip instead of two, and no
 * race between the check and the insert.
 */
public final class ConstraintViolations {

    private ConstraintViolations() {
    }

    /**
     * The name of the violated constraint or unique index, or null if the
     * database did not report one.
     */
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
