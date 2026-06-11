package com.asa.pay.exception;

public class ExternalApiUnavailableException extends RuntimeException {
    public ExternalApiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
