package com.loopers.infrastructure.product;

import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageWindow;
import com.loopers.domain.common.PageSize;
import com.loopers.domain.product.ProductViewQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ProductViewQueryImpl implements ProductViewQuery {

    private static final String SELECT_COLUMNS = """
        SELECT p.id, p.name, p.price, p.quantity, b.id, b.name,
               COUNT(DISTINCT l.id) AS like_count,
               MAX(CASE WHEN ml.id IS NULL THEN 0 ELSE 1 END) AS liked
        """;

    private static final String JOINS = """
          JOIN brand b ON b.id = p.brand_id
          LEFT JOIN product_like l ON l.product_id = p.id
          LEFT JOIN product_like ml ON ml.product_id = p.id AND ml.user_id = :viewerId
        """;

    private static final String GROUP_BY = " GROUP BY p.id, p.name, p.price, p.quantity, b.id, b.name ";

    private final EntityManager entityManager;

    @Override
    public Optional<ProductViewQuery.View> findAliveById(Long productId, Long viewerId) {
        Query query = entityManager.createNativeQuery(
            SELECT_COLUMNS + " FROM product p " + JOINS
                + " WHERE p.id = :productId AND p.deleted_at IS NULL " + GROUP_BY);
        query.setParameter("productId", productId);
        query.setParameter("viewerId", viewerId);

        List<?> rows = query.getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(toView((Object[]) rows.get(0)));
    }

    @Override
    public PageWindow<ProductViewQuery.View> findPage(ProductViewQuery.Criteria criteria) {
        String where = " WHERE p.deleted_at IS NULL "
            + (criteria.brandId() != null ? " AND p.brand_id = :brandId " : "");

        Query query = entityManager.createNativeQuery(
            SELECT_COLUMNS + " FROM product p " + JOINS + where + GROUP_BY
                + " ORDER BY " + orderByOf(criteria.sort())
                + " LIMIT :limit OFFSET :offset");
        query.setParameter("viewerId", criteria.viewerId());
        query.setParameter("limit", PageWindow.limitOf(criteria.size()));
        query.setParameter("offset", criteria.offset());
        if (criteria.brandId() != null) {
            query.setParameter("brandId", criteria.brandId());
        }

        List<ProductViewQuery.View> rows = ((List<?>) query.getResultList()).stream()
            .map(row -> toView((Object[]) row))
            .toList();

        return PageWindow.of(rows, criteria.size());
    }

    @Override
    public List<ProductViewQuery.View> findLikedBy(Long userId, PageNumber page, PageSize size) {
        Query query = entityManager.createNativeQuery("""
            SELECT p.id, p.name, p.price, p.quantity, b.id, b.name,
                   COUNT(DISTINCT l.id) AS like_count, 1 AS liked
              FROM product_like ml
              JOIN product p ON p.id = ml.product_id AND p.deleted_at IS NULL
              JOIN brand b ON b.id = p.brand_id
              LEFT JOIN product_like l ON l.product_id = p.id
             WHERE ml.user_id = :userId
             GROUP BY p.id, p.name, p.price, p.quantity, b.id, b.name
             ORDER BY MAX(ml.id) DESC
             LIMIT :limit OFFSET :offset
            """);
        query.setParameter("userId", userId);
        query.setParameter("limit", size.value());
        query.setParameter("offset", page.offsetWith(size));

        return ((List<?>) query.getResultList()).stream()
            .map(row -> toView((Object[]) row))
            .toList();
    }

    private static String orderByOf(ProductViewQuery.Sort sort) {
        return switch (sort) {
            case LATEST -> "p.id DESC";
            case PRICE_ASC -> "p.price ASC, p.id DESC";
            case LIKES_DESC -> "like_count DESC, p.id DESC";
        };
    }

    private ProductViewQuery.View toView(Object[] row) {
        return new ProductViewQuery.View(
            ((Number) row[0]).longValue(),
            (String) row[1],
            ((Number) row[2]).longValue(),
            ((Number) row[3]).intValue() == 0,
            ((Number) row[4]).longValue(),
            (String) row[5],
            ((Number) row[6]).longValue(),
            ((Number) row[7]).intValue() == 1
        );
    }
}
