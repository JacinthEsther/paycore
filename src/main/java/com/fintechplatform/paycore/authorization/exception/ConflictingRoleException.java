package com.fintechplatform.paycore.authorization.exception;

public class ConflictingRoleException extends RuntimeException {

    public ConflictingRoleException(String roleName, String heldRole) {
        super("%s cannot be given to someone who is %s: one person must not hold both duties"
                .formatted(roleName, heldRole));
    }
}
