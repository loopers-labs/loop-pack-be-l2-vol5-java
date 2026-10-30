package com.loopers.batch.job.reconcile;

// 대사는 운영 도구라 orders와 processed_order_confirmation을 함께 읽는다(설계 27절 D75). api·streamer 엔티티에 의존하지 않고
// 같은 MySQL을 SQL로 읽으며, DB가 나뉘면 양쪽을 따로 읽어 비교해야 한다. (status, confirmed_at) 인덱스로 구간을 고른다.
public final class ReconcileQueries {
    public static final String WINDOW_CONDITION = "o.status = 'CONFIRMED' and o.confirmed_at < :toAt"
        + " and (o.confirmed_at > :fromAt or (o.confirmed_at = :fromAt and o.id > :fromOrderId))";
    public static final String MISSING_CONDITION = WINDOW_CONDITION
        + " and not exists (select 1 from processed_order_confirmation p where p.order_id = o.id)";
    public static final String COUNT_CONFIRMED = "select count(*) from orders o where " + WINDOW_CONDITION;

    private ReconcileQueries() {
    }
}
