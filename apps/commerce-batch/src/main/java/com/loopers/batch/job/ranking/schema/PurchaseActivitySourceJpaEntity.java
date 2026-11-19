package com.loopers.batch.job.ranking.schema;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.FractionalSeconds;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * commerce-streamer가 소유하는 구매 활동을 읽기 전용으로 매핑한다. 배치는 이 테이블에 쓰지 않는다.
 * 점수 고정이 기간 범위로 읽도록 confirmed_at 인덱스를 선언한다.
 * 스키마의 원본은 docker/mysql의 SQL 파일이다. 이 매핑은 local의 스키마 확인(validate)과 test 스키마 생성에만 쓰고,
 * 읽기·쓰기는 JdbcTemplate SQL로 한다.
 */
@Entity
@Immutable
@Table(
    name = "product_purchase_activity",
    uniqueConstraints = @UniqueConstraint(name = "uk_purchase_activity_order_product", columnNames = {"order_id", "product_id"}),
    indexes = @Index(name = "idx_purchase_activity_confirmed_at", columnList = "confirmed_at")
)
public class PurchaseActivitySourceJpaEntity extends BaseEntity {

    @Column(name = "order_id", nullable = false)
    private long orderId;

    @Column(name = "product_id", nullable = false)
    private long productId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "confirmed_at", nullable = false)
    @FractionalSeconds(6)
    private Instant confirmedAt;

    protected PurchaseActivitySourceJpaEntity() {
    }
}
