package com.fdic.tip.notification.eventhub;

public class EventHubPublishException extends RuntimeException {
    public EventHubPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
