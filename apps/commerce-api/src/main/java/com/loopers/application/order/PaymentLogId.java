package com.loopers.application.order;

import java.util.regex.Pattern;

final class PaymentLogId {
    private static final Pattern SYNTHETIC_ID = Pattern.compile("(?:LP-ORD-[0-9]{10,19}|TEST-TX-[0-9]{1,10})");

    private PaymentLogId() { }

    static String safe(String id) {
        if (id == null) {
            return "NONE";
        }
        // External/legacy values can contain arbitrary text. The internal order and attempt still identify the operation.
        return SYNTHETIC_ID.matcher(id).matches() ? id : "REDACTED";
    }
}
