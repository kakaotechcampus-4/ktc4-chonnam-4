package com.neuringo.neuringobe.common;

import org.springframework.http.HttpStatus;

public class ApiDomainException extends ApiException {

    public ApiDomainException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }
}
