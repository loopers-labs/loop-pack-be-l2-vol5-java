package com.loopers.batch.perf;

import com.querydsl.core.types.dsl.DateTimePath;
import com.querydsl.core.types.dsl.NumberPath;
import com.querydsl.core.types.dsl.StringPath;
import com.querydsl.sql.ColumnMetadata;
import com.querydsl.sql.RelationalPathBase;

import java.sql.Types;
import java.time.LocalDateTime;

import static com.querydsl.core.types.PathMetadataFactory.forVariable;

/**
 * Querydsl-SQL이 쓰는 ranking_snapshot_input 테이블 경로 (W9 batch insert 실험).
 * 엔티티로 만든 JPA용 Q클래스(QRankingSnapshotInput)는 SQLQueryFactory에 쓸 수 없어서 손으로 적었다.
 * 운영에서 쓴다면 DB 스키마를 읽어 이 클래스를 만드는 querydsl-sql-codegen을 쓴다.
 */
public class SRankingSnapshotInput extends RelationalPathBase<SRankingSnapshotInput> {

    public static final SRankingSnapshotInput INPUT = new SRankingSnapshotInput("input");

    public final StringPath snapshotId = createString("snapshotId");
    public final NumberPath<Long> productId = createNumber("productId", Long.class);
    public final NumberPath<Long> orderId = createNumber("orderId", Long.class);
    public final NumberPath<Integer> quantity = createNumber("quantity", Integer.class);
    public final DateTimePath<LocalDateTime> confirmedAt = createDateTime("confirmedAt", LocalDateTime.class);
    public final DateTimePath<LocalDateTime> createdAt = createDateTime("createdAt", LocalDateTime.class);
    public final DateTimePath<LocalDateTime> updatedAt = createDateTime("updatedAt", LocalDateTime.class);

    public SRankingSnapshotInput(String variable) {
        super(SRankingSnapshotInput.class, forVariable(variable), null, "ranking_snapshot_input");
        addMetadata(snapshotId, ColumnMetadata.named("snapshot_id").ofType(Types.VARCHAR).withSize(32).notNull());
        addMetadata(productId, ColumnMetadata.named("product_id").ofType(Types.BIGINT).notNull());
        addMetadata(orderId, ColumnMetadata.named("order_id").ofType(Types.BIGINT).notNull());
        addMetadata(quantity, ColumnMetadata.named("quantity").ofType(Types.INTEGER).notNull());
        addMetadata(confirmedAt, ColumnMetadata.named("confirmed_at").ofType(Types.TIMESTAMP).notNull());
        addMetadata(createdAt, ColumnMetadata.named("created_at").ofType(Types.TIMESTAMP).notNull());
        addMetadata(updatedAt, ColumnMetadata.named("updated_at").ofType(Types.TIMESTAMP).notNull());
    }
}
