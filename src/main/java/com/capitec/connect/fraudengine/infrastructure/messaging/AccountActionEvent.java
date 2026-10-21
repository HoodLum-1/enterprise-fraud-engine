package com.capitec.connect.fraudengine.infrastructure.messaging;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record AccountActionEvent(
    UUID eventId,
    String eventType,
    UUID transactionId,
    String accountId,
    String reason,
    Instant timestamp
) {}
