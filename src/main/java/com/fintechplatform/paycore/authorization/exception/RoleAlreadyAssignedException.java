package com.fintechplatform.paycore.authorization.exception;

public class RoleAlreadyAssignedException extends RuntimeException {

    public RoleAlreadyAssignedException(String roleName) {
        super("Customer already has role %s".formatted(roleName));
    }
}
