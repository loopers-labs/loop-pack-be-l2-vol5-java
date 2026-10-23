package com.loopers.fixture.pg;

/** plan FIFO 의 대상 구분이자 journal 의 요청 구분. 대상은 각각 orderId·transactionKey·orderId 다. */
public enum PgOperation {
    CREATE,
    LOOKUP_TRANSACTION,
    LOOKUP_ORDER
}
