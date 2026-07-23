package gov.fdic.tip.service;

import gov.fdic.tip.domain.NotificationDelivery;
import gov.fdic.tip.domain.NotificationDeliveryStatus;
import gov.fdic.tip.repository.NotificationDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * NotificationEventHubConsumer only re-triggers dispatch when Event Hub
 * redelivers a message (crash/restart/rebalance) - a transient email-api
 * failure that leaves a delivery PENDING (see
 * NotificationDeliveryDispatchService.handleFailure) has nothing else that
 * will retry it, since the original Event Hub message was already
 * checkpointed successfully (the CONSUME succeeded; only the downstream
 * EMAIL SEND failed). This sweep exists to close that gap.
 */
@Component
public class PendingDeliveryRetrySweep {

    private static final Logger LOG = LoggerFactory.getLogger(PendingDeliveryRetrySweep.class);

    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationDeliveryDispatchService dispatchService;

    public PendingDeliveryRetrySweep(NotificationDeliveryRepository deliveryRepository,
                                      NotificationDeliveryDispatchService dispatchService) {
        this.deliveryRepository = deliveryRepository;
        this.dispatchService = dispatchService;
    }

    @Scheduled(fixedDelay = 60_000) // every 60s - tune based on acceptable retry latency vs DB load
    public void retryPending() {
        List<NotificationDelivery> pending = deliveryRepository.findByStatus(NotificationDeliveryStatus.PENDING);
        if (pending.isEmpty()) {
            return;
        }
        LOG.debug("Retry sweep found {} PENDING deliveries", pending.size());
        for (NotificationDelivery delivery : pending) {
            dispatchService.dispatchOne(delivery);
        }
    }
}
