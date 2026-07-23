package gov.fdic.tip.commons.notification;

/** Base type for anything going wrong when calling the notification service. */
public class NotificationPublishException extends Exception {
    public NotificationPublishException(String message, Throwable cause) {
        super(message, cause);
    }
    public NotificationPublishException(String message) {
        super(message);
    }
}
