package com.loopers.domain.activity;

public enum UnreadableReason {
    BLANK_EVENT_ID,
    MISSING_ORDER_ID,
    KEY_MISMATCH,
    MISSING_CONFIRMED_AT,
    EMPTY_ITEMS,
    MISSING_PRODUCT_ID,
    NON_POSITIVE_QUANTITY,
    DUPLICATE_PRODUCT,
    MALFORMED
}
