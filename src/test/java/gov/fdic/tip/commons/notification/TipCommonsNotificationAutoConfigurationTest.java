package gov.fdic.tip.commons.notification;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This test simulates exactly what happens in a CONSUMING service that adds
 * tip-commons-notification as a dependency: no @Import, no @ComponentScan
 * change, no manual @Bean - just the jar on the classpath plus a few
 * application.yml properties. If this test passes, any Spring Boot service
 * gets NotificationPublishClient auto-wired for free the same way.
 */
class TipCommonsNotificationAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TipCommonsNotificationAutoConfiguration.class));

    @Test
    void beanIsWiredAutomaticallyWithMinimalConfig() {
        contextRunner
                .withPropertyValues(
                        "tip.notification-publish.base-url=https://tip-notification.internal.fdic.gov",
                        "tip.notification-publish.auth-mode=client-secret",
                        "tip.notification-publish.tenant-id=test-tenant",
                        "tip.notification-publish.client-id=test-client",
                        "tip.notification-publish.client-secret=test-secret",
                        "tip.notification-publish.token-scope=api://tip-notification-service/.default"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(NotificationPublishClient.class);
                    assertThat(context).hasSingleBean(NotificationPublishEngine.class);
                });
    }

    @Test
    void disabledViaPropertyMeansNoBean() {
        contextRunner
                .withPropertyValues("tip.notification-publish.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(NotificationPublishClient.class));
    }
}
