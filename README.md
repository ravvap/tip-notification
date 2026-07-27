# tip-commons-notification

A shared, high-performance Java core library designed to allow any TIP service (Spring Boot Microservices or Standalone Batch Utilities) to stream notification events directly into **Azure Event Hubs**. 

This library completely bypasses the need for an external, synchronous notification microservice HTTP endpoint, eliminating the risk of missing critical notification events during transient network blips or microservice outages. It features built-in automatic retry logic managed natively by the Azure SDK and leverages an **Idempotency Key as an Event Hub Partition Key** to enforce ordered message processing downstream.

---

## Table of Contents
1. [Prerequisites & Dependencies](#1-prerequisites--dependencies)
2. [Configuration Properties](#2-configuration-properties)
3. [Spring Boot Service Implementation Guide](#3-spring-boot-service-implementation-guide)
4. [Standalone Batch Job Implementation Guide](#4-standalone-batch-job-implementation-guide)
5. [Architecture & Best Practices](#5-architecture--best-practices)

---

## 1. Prerequisites & Dependencies

To use this library across your services, add the `tip-commons-notification` JAR dependency alongside the official Azure Event Hubs SDK in your project's `pom.xml`:

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

Configure the library inside your application's application.yml or application.properties. The library automatically binds configuration keys matching the prefix tip.notification-publish.*.

Option A: Connection String Authentication (SAS Token)

tip:
  notification-publish:
    enabled: true
    auth-mode: connection-string
    connection-string: "Endpoint=sb://your-namespace.servicebus.windows.net/;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=your-primary-key-goes-here"
    event-hub-name: "notification-events"
    

Option B: Azure Entra ID Managed Identity

tip:
  notification-publish:
    enabled: true
    auth-mode: managed-identity
    namespace-fully-qualified-domain-name: "your-namespace.servicebus.windows.net"
    event-hub-name: "notification-events"
    
3. Spring Boot Service Implementation Guide    

Step 1: Define Your Inbound Request DTO
Create a custom object mapping incoming testing request payloads.

package com.example.myservice.dto;

import lombok.Data;
import java.util.Map;

@Data
public class TestNotificationRequest {
    private String eventType;        // e.g., "LOAN_APPLICATION_SUBMITTED"
    private String recipientEmail;   // e.g., "dev-team@fdic.gov"
    private String orderId;          // Core entity ID utilized for partition keys
    private Map<String, Object> details; // Metadata context dictionary
}

Step 2: Inject Client into the Business Service Layer
Inject the library-provided NotificationPublishClient interface to assemble and dispatch requests.

package com.example.myservice.service;

import com.example.myservice.dto.TestNotificationRequest;
import gov.fdic.tip.commons.notification.NotificationPublishClient;
import gov.fdic.tip.commons.notification.NotificationPublishException;
import gov.fdic.tip.commons.notification.NotificationPublishRequest;
import gov.fdic.tip.commons.notification.NotificationPublishResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationTestService {

    // Automatically autowired by TipCommonsNotificationAutoConfiguration
    private final NotificationPublishClient notificationPublishClient;

    public String sendTestNotification(TestNotificationRequest testRequest) {
        log.info("Processing test notification dispatch for event: {}", testRequest.getEventType());

        // Establish a stable Idempotency Key based on your database entity
        String idempotencyKey = "txn-" + testRequest.getOrderId() + "-" + testRequest.getEventType();

        // Build the immutable request structure
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
            // Transmit directly into Azure Event Hubs synchronously
            NotificationPublishResponse response = notificationPublishClient.publish(publishRequest);
            
            log.info("Successfully streamed to Event Hub! Event Tracking ID: {}", response.notificationEventId());
            return "Success! Event Hub tracking ID: " + response.notificationEventId();

        } catch (NotificationPublishException e) {
            log.error("Failed to commit event payload into Azure Event Hub log stream", e);
            return "Failed to send event: " + e.getMessage();
        }
    }
}

Step 3: Expose via Rest Controller
Expose a simple POST REST interface for operational testing or tracking.

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
        } else {
            return ResponseEntity.internalServerError().body(result);
        }
    }
}

Step 4: Run a Test Request
Execute a cURL execution block directly against your running instance:

curl -X POST http://localhost:8080/api/test-notification \
  -H "Content-Type: application/json" \
  -d '{
    "eventType": "LOAN_APPLICATION_SUBMITTED",
    "recipientEmail": "dev-team@fdic.gov",
    "orderId": "987654",
    "details": {
      "applicantName": "John Doe",
      "amountRequested": 250000,
      "riskScore": "LOW"
    }
  }'
  
  
4. Standalone Batch Job Implementation Guide
For utility scripts, legacy batch routines, or environments where a full Spring Dependency Injection context is absent, developers must utilize the thread-safe static interface wrapper: NotificationPublishUtil.


Step 1: Initialize at Application Bootstrap
Call .configure() exactly once at the entry point of your batch run lifecycle (e.g., inside the public static void main method).
package com.example.batch;

import gov.fdic.tip.commons.notification.NotificationPublishUtil;

public class StandaloneBatchApplication {

    public static void main(String[] args) {
        System.out.println("Initializing Batch Routine Framework Context...");

        // Pull these environments parameters out of your system variables or property loader
        String connectionString = System.getenv("EVENT_HUB_CONNECTION_STRING");
        String eventHubName = "notification-events";

        // Initialize the internal static publishing engine engine once
        NotificationPublishUtil.configure(connectionString, eventHubName);

        // Alternatively, if deploying batches to Azure utilizing Managed Identity:
        // NotificationPublishUtil.configureWithManagedIdentity("yournamespace.servicebus.windows.net", eventHubName);

        // Kickoff application batch workflow logic
        executeBatchProcess();
    }
}

Step 2: Publish Events from Anywhere Inside Your Processing Logic
Once initialized, developers can safely issue fire-and-forget or audited publication streams from anywhere in the multi-threaded batch executor.

package com.example.batch.task;

import gov.fdic.tip.commons.notification.*;
import java.util.Map;

public class FileProcessingTask {

    public void processRecord(String recordId, String userEmail) {
        // ... Business Processing Steps ...

        try {
            // Assembling Notification Payload
            NotificationPublishRequest batchEventRequest = NotificationPublishRequest.builder()
                    .source("COMPLIANCE_BATCH_JOB")
                    .eventType("RECORD_PROCESSED")
                    .idempotencyKey("batch-rec-" + recordId) // Ensures strict log partitioning
                    .recipientEmail(userEmail)
                    .context(Map.of("recordId", recordId, "status", "PROCESSED_SUCCESSFULLY"))
                    .build();

            // Execute via static Proxy Util wrapper 
            NotificationPublishResponse result = NotificationPublishUtil.publish(batchEventRequest);
            System.out.println("Successfully committed record notification event. Tracking ID: " + result.notificationEventId());

        } catch (NotificationPublishException e) {
            System.err.println("Critical Failure: Unable to publish notification to Event Hub namespace: " + e.getMessage());
            // Take appropriate local fallback steps (e.g., logging to local outbox table)
        }
    }
}



