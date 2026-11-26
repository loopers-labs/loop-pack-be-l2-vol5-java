package com.loopers.domains.order.app;

import java.util.List;

public record ReconcileBatchResult(int requested, int processed, StopReason stopReason, List<Item> results) {
    public enum StopReason { CONSECUTIVE_FAILURES }

    public enum Outcome { RESOLVED, STOPPED, ALREADY_CLOSED, NOT_APPLICABLE, SKIPPED }

    public enum Assessment { VERIFIED, UNCERTAIN, REVIEW, NOT_CALLED }

    public record Item(long orderId, String beforeStatus, String afterStatus, String beforeProviderStatus,
        String afterProviderStatus, Assessment assessment, Outcome outcome, String reason) { }
}
