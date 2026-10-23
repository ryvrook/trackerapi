package fakes.tamework.api;

import java.time.Instant;
import java.util.UUID;

public record ActivityHeader(UUID operationId, long sequence, String actionId, Instant occurredAt) {
}
