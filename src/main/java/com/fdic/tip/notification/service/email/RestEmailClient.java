package com.fdic.tip.notification.service.email;

import com.fdic.tip.notification.dto.EmailRequestDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Map;

/**
 * Default EmailNotifier implementation that calls YOUR EXISTING email-api
 * over HTTP. This is the integration point - adjust:
 *   1. EmailApiProperties (base URL, auth mode) in application.yml
 *   2. The request body shape in buildRequestBody() to match your API's contract
 *   3. The response/error handling in send() to match your API's response codes
 *
 * If your email-api already has a Java client/SDK, you can skip RestClient
 * entirely and just implement EmailNotifier by calling that SDK directly -
 * NotificationService doesn't care how this interface is fulfilled.
 */
@Slf4j
@Component
public class RestEmailClient implements EmailNotifier {

    private final RestClient restClient;
    private final EmailApiProperties properties;

    public RestEmailClient(EmailApiProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .build();
    }

    @Override
    public void send(EmailRequestDto request) throws EmailDeliveryException {
        try {
            var requestSpec = restClient.post()
                    .uri(properties.getSendPath())
                    .contentType(MediaType.APPLICATION_JSON);

            // --- Auth: adjust to match how your existing email-api authenticates ---
            if ("bearer".equalsIgnoreCase(properties.getAuthMode())) {
                requestSpec = requestSpec.header("Authorization", "Bearer " + properties.getBearerToken());
            } else {
                requestSpec = requestSpec.header(properties.getApiKeyHeaderName(), properties.getApiKey());
            }

            requestSpec
                    .body(buildRequestBody(request))
                    .retrieve()
                    .toBodilessEntity();

            log.info("Email dispatched via email-api for notificationId={}", request.notificationId());

        } catch (RestClientException e) {
            // Caller (NotificationService) records this as a FAILED attempt and can
            // schedule a retry / dead-letter after max attempts - see EmailDeliveryAttemptEntity.
            throw new EmailDeliveryException(
                    "email-api call failed for notificationId=" + request.notificationId(), e);
        }
    }

    /**
     * CUSTOMIZE THIS to match your existing email-api's expected request schema.
     * Placeholder shape shown below - swap field names/nesting as needed.
     */
    private Map<String, Object> buildRequestBody(EmailRequestDto request) {
        return Map.of(
                "recipient", request.recipientUserId(),
                "subject", request.subject(),
                "body", request.body(),
                "metadata", Map.of(
                        "notificationId", request.notificationId().toString(),
                        "noticeType", request.noticeType()
                )
        );
    }
}
