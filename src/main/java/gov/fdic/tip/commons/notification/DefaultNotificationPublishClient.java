package gov.fdic.tip.commons.notification;

public class DefaultNotificationPublishClient implements NotificationPublishClient {

    private final NotificationPublishEngine engine;

    public DefaultNotificationPublishClient(NotificationPublishEngine engine) {
        this.engine = engine;
    }

    @Override
    public NotificationPublishResponse publish(NotificationPublishRequest request) throws NotificationPublishException {
        return engine.publish(request);
    }
}
