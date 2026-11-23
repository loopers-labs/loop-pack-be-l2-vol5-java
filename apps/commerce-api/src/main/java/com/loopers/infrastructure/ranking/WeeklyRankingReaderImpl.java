package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.PublishedRevision;
import com.loopers.domain.ranking.RankedProduct;
import com.loopers.domain.ranking.WeeklyRankingReader;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 랭킹 테이블을 JPA로 읽는다. 시각은 Hibernate의 UTC 설정대로 해석되어 UTC Instant가 된다.
 */
@Repository
public class WeeklyRankingReaderImpl implements WeeklyRankingReader {

    private final EntityManager entityManager;

    public WeeklyRankingReaderImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<PublishedRevision> findPublished(LocalDate periodStart) {
        return entityManager.createQuery("""
                SELECT r.revision AS revision, r.calculatedAt AS calculatedAt
                FROM WeeklyRankingRevisionJpaEntity r
                WHERE r.periodStart = :periodStart AND r.status = 'COMPLETED'
                ORDER BY r.revision DESC""", Tuple.class)
            .setParameter("periodStart", periodStart)
            .setMaxResults(1)
            // 트랜잭션 밖의 공유 EntityManager는 쿼리 호출이 끝나면 닫히므로 스트림이 아니라 목록으로 받는다.
            .getResultList()
            .stream()
            .findFirst()
            .map(row -> new PublishedRevision(row.get("revision", Integer.class), row.get("calculatedAt", Instant.class)));
    }

    // 삭제된 상품을 거른 뒤 limit을 적용한다. 상품의 공개 조건과 표시 정보는 고객 상품 조회와 같다.
    @Override
    public List<RankedProduct> findRankedProducts(LocalDate periodStart, int revision, int limit) {
        return entityManager.createQuery("""
                SELECT s.rankNo AS rankNo, s.score AS score, pr.id AS productId, pr.name AS productName,
                       pr.price AS price, b.id AS brandId, b.name AS brandName
                FROM WeeklyRankingScoreJpaEntity s
                JOIN ProductJpaEntity pr ON pr.id = s.productId AND pr.deletedAt IS NULL
                JOIN pr.brand b
                WHERE s.periodStart = :periodStart AND s.revision = :revision
                ORDER BY s.rankNo""", Tuple.class)
            .setParameter("periodStart", periodStart)
            .setParameter("revision", revision)
            .setMaxResults(limit)
            .getResultList()
            .stream()
            .map(row -> new RankedProduct(
                row.get("rankNo", Integer.class),
                row.get("score", Long.class),
                row.get("productId", Long.class),
                row.get("productName", String.class),
                row.get("price", Long.class),
                row.get("brandId", Long.class),
                row.get("brandName", String.class)))
            .toList();
    }
}
