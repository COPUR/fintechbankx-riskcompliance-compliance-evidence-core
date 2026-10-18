package com.bank.compliance.domain.port.out;

import com.bank.compliance.domain.ComplianceScreenedEvent;

/**
 * Publishes compliance domain events. Implementations must write the event in
 * the same transaction as the screening result it describes (transactional
 * outbox), so a stored result and its event commit or roll back together.
 */
public interface ComplianceEventPublisher {
    void publish(ComplianceScreenedEvent event);
}
