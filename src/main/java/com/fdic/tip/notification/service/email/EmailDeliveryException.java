package com.fdic.tip.notification.service.email;

public class EmailDeliveryException extends Exception {
    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }

    public EmailDeliveryException(String message) {
        super(message);
    }
}
