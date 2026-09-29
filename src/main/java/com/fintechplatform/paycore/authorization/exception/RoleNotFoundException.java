package com.fintechplatform.paycore.authorization.exception;

public class RoleNotFoundException extends RuntimeException {

    public RoleNotFoundException(String roleName) {
        super("Role %s does not exist".formatted(roleName));
    }
}
