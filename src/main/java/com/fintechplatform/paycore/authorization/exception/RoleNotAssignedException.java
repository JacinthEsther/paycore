package com.fintechplatform.paycore.authorization.exception;

public class RoleNotAssignedException extends RuntimeException {

    public RoleNotAssignedException(String roleName) {
        super("Customer does not have role %s".formatted(roleName));
    }
}
