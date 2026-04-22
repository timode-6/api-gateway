package com.innowise.api_gateway.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class RegistrationException extends RuntimeException {

    private final HttpStatus status;

    public RegistrationException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }
}