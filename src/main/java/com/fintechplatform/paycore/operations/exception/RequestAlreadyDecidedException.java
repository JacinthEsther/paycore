package com.fintechplatform.paycore.operations.exception;

import com.fintechplatform.paycore.operations.enums.OperationsRequestStatus;

public class RequestAlreadyDecidedException extends RuntimeException {

    public RequestAlreadyDecidedException(OperationsRequestStatus status) {
        super("The request has already been " + status.name().toLowerCase());
    }
}
