package com.fdic.tip.notification.service.email;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bind these under `tip.email-api.*` in application.yml (or via Azure Key
 * Vault / env vars, matching your existing pattern from the Quartz Scheduler
 * app's Key Vault bearer token fetch).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "tip.email-api")
public class EmailApiProperties {

    /** Base URL of your existing email-api, e.g. https://internal.fdic.gov/email-api */
    private String baseUrl;

    /** Path to POST to, appended to baseUrl. Adjust to match your existing API's contract. */
    private String sendPath = "/v1/messages";

    /** Auth mode: "api-key" (static header) or "bearer" (token fetched externally, e.g. Key Vault). */
    private String authMode = "api-key";

    /** Used when authMode = api-key. */
    private String apiKeyHeaderName = "X-API-Key";
    private String apiKey;

    /** Used when authMode = bearer - supply a token however your existing app does today. */
    private String bearerToken;

    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 5000;
}
