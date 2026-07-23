package gov.fdic.tip.commons.notification;

/**
 * Spring-friendly wrapper around NotificationPublishEngine, matching the
 * RetentionEngine/RetentionService layering pattern already used in
 * tip-commons. Most consumers should just @Autowire this interface (bean
 * provided by TipCommonsNotificationAutoConfiguration).
 *
 * DELIBERATELY named "Client", not "Service" - the notification service
 * itself already has a class called NotificationPublishService
 * (gov.fdic.tip.service.NotificationPublishService) that does the actual
 * server-side persistence/dispatch logic. Reusing that name here, even in a
 * different package, would be confusing in logs, stack traces, and IDE
 * autocomplete for anyone working across both codebases.
 */
public interface NotificationPublishClient {
    NotificationPublishResponse publish(NotificationPublishRequest request) throws NotificationPublishException;
}
