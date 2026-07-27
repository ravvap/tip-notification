# tip-commons-notification

A shared, high-performance Java core library designed to allow any TIP service (Spring Boot microservices or standalone batch utilities) to stream notification events directly into Azure Event Hubs.

This library completely bypasses the need for an external, synchronous notification microservice HTTP endpoint, eliminating the risk of missing critical notification events during transient network blips or microservice outages. It features built-in automatic retry logic managed natively by the Azure SDK and leverages an Idempotency Key as an Event Hub Partition Key to enforce ordered message processing downstream.

---

## Table of Contents

1. [Prerequisites & Dependencies](#1-prerequisites--dependencies)
2. [Configuration Properties](#2-configuration-properties)
3. [Spring Boot Service Implementation Guide](#3-spring-boot-service-implementation-guide)
4. [Standalone Batch Job Implementation Guide](#4-standalone-batch-job-implementation-guide)
5. [Architecture & Best Practices](#5-architecture--best-practices)

---

## 1. Prerequisites & Dependencies

Add the `tip-commons-notification` JAR dependency alongside the Azure Event Hubs SDK in your project's `pom.xml`:

```xml
<dependencies>
    <dependency>
        <groupId>gov.fdic.tip</groupId>
        <artifactId>tip-commons-notification</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </dependency>

    <dependency>
        <groupId>com.azure</groupId>
        <artifactId>azure-messaging-eventhubs</artifactId>
        <version>5.15.0</version>
    </dependency>
</dependencies>
```

## 2. Configuration Properties

Configure the library in `application.yml` or `application.properties`. The library automatically binds configuration keys matching the prefix `tip.notification-publish.*`.

### Option A: Connection String Authentication (SAS Token)

```yaml
tip:
  notification-publish:
    enabled: true
    auth-mode: connection-string
    connection-string: "Endpoint=sb://your-namespace.servicebus.windows.net/;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=your-primary-key-goes-here"
    event-hub-name: "notification-events"
```

### Option B: Azure Entra ID Managed Identity

```yaml
tip:
  notification-publish:
    enabled: true
    auth-mode: managed-identity
    namespace-fully-qualified-domain-name: "your-namespace.servicebus.windows.net"
    event-hub-name: "notification-events"
```

## 3. Spring Boot Service Implementation Guide

### Step 1: Define Your Inbound Request DTO

```java
package com.example.myservice.dto;

import lombok.Data;
import java.util.Map;

@Data
public class TestNotificationRequest {
    private String eventType;
    private String recipientEmail;
    private String orderId;
    private Map<String, Object> details;
}
```

### Step 2: Inject Client into the Business Service Layer

```java
package com.example.myservice.service;

import com.example.myservice.dto.TestNotificationRequest;
import gov.fdic.tip.commons.notification.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationTestService {

    private final NotificationPublishClient notificationPublishClient;

    public String sendTestNotification(TestNotificationRequest testRequest) {
        String idempotencyKey = "txn-" + testRequest.getOrderId() + "-" + testRequest.getEventType();

        NotificationPublishRequest publishRequest = NotificationPublishRequest.builder()
                .eventId(UUID.randomUUID().toString())
                .source("MY_SPRING_MICROSERVICE")
                .eventType(testRequest.getEventType())
                .severity("INFO")
                .idempotencyKey(idempotencyKey)
                .recipientEmail(testRequest.getRecipientEmail())
                .context(testRequest.getDetails())
                .build();

        try {
            NotificationPublishResponse response = notificationPublishClient.publish(publishRequest);
            return "Success! Event Hub tracking ID: " + response.notificationEventId();
        } catch (NotificationPublishException e) {
            return "Failed to send event: " + e.getMessage();
        }
    }
}
```

### Step 3: Expose via REST Controller

```java
package com.example.myservice.controller;

import com.example.myservice.dto.TestNotificationRequest;
import com.example.myservice.service.NotificationTestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class NotificationTestController {

    private final NotificationTestService notificationTestService;

    @PostMapping("/test-notification")
    public ResponseEntity<String> triggerNotification(@RequestBody TestNotificationRequest request) {
        String result = notificationTestService.sendTestNotification(request);

        if (result.startsWith("Success")) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.internalServerError().body(result);
    }
}
```

### Step 4: Run a Test Request

```bash
curl -X POST http://localhost:8080/api/test-notification   -H "Content-Type: application/json"   -d '{
    "eventType": "LOAN_APPLICATION_SUBMITTED",
    "recipientEmail": "dev-team@fdic.gov",
    "orderId": "987654",
    "details": {
      "applicantName": "John Doe",
      "amountRequested": 250000,
      "riskScore": "LOW"
    }
  }'
```

## 4. Standalone Batch Job Implementation Guide

### Step 1: Initialize at Application Bootstrap

```java
package com.example.batch;

import gov.fdic.tip.commons.notification.NotificationPublishUtil;

public class StandaloneBatchApplication {

    public static void main(String[] args) {
        String connectionString = System.getenv("EVENT_HUB_CONNECTION_STRING");
        String eventHubName = "notification-events";

        NotificationPublishUtil.configure(connectionString, eventHubName);
        executeBatchProcess();
    }

    private static void executeBatchProcess() {
        // Batch processing logic.
    }
}
```

### Step 2: Publish Events During Processing

```java
package com.example.batch.task;

import gov.fdic.tip.commons.notification.*;
import java.util.Map;

public class FileProcessingTask {

    public void processRecord(String recordId, String userEmail) {
        try {
            NotificationPublishRequest request = NotificationPublishRequest.builder()
                    .source("COMPLIANCE_BATCH_JOB")
                    .eventType("RECORD_PROCESSED")
                    .idempotencyKey("batch-rec-" + recordId)
                    .recipientEmail(userEmail)
                    .context(Map.of("recordId", recordId, "status", "PROCESSED_SUCCESSFULLY"))
                    .build();

            NotificationPublishResponse response = NotificationPublishUtil.publish(request);
            System.out.println(response.notificationEventId());
        } catch (NotificationPublishException e) {
            System.err.println(e.getMessage());
        }
    }
}
```

## 5. Architecture & Best Practices

- Use stable idempotency keys for message ordering.
- Prefer Managed Identity for Azure-hosted deployments.
- Initialize `NotificationPublishUtil` only once in standalone batch applications.
- Allow the Azure SDK retry mechanism to handle transient failures.
