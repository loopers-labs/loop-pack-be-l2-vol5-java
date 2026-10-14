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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class ProductViewQueryImpl implements ProductViewQuery {

    private static final String VIEW_COLUMNS = """
        SELECT p.id, p.name, p.price, p.quantity, b.id, b.name,
               (SELECT COUNT(*) FROM product_like l WHERE l.product_id = p.id) AS like_count,
               EXISTS (SELECT 1 FROM product_like ml WHERE ml.user_id = :viewerId AND ml.product_id = p.id) AS liked
        """;

    private final EntityManager entityManager;

    @Override
    public Optional<ProductViewQuery.View> findAliveById(Long productId, Long viewerId) {
        Query query = entityManager.createNativeQuery(
            VIEW_COLUMNS + " FROM product p JOIN brand b ON b.id = p.brand_id "
                + " WHERE p.id = :productId AND p.deleted_at IS NULL ");
        query.setParameter("productId", productId);
        query.setParameter("viewerId", viewerId);

        List<?> rows = query.getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(toView((Object[]) rows.get(0)));
    }

    @Override
    public PageWindow<ProductViewQuery.View> findPage(ProductViewQuery.Criteria criteria) {
        String sql = criteria.sort() == ProductViewQuery.Sort.LIKES_DESC
            ? likesRankedPage(criteria)
            : pickedPage(criteria);

        Query query = entityManager.createNativeQuery(sql);
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
    public PageWindow<ProductViewQuery.View> findLikedBy(Long userId, PageNumber page, PageSize size) {
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
        query.setParameter("limit", PageWindow.limitOf(size));
        query.setParameter("offset", page.offsetWith(size));

        List<ProductViewQuery.View> rows = ((List<?>) query.getResultList()).stream()
            .map(row -> toView((Object[]) row))
            .toList();

        return PageWindow.of(rows, size);
    }

    @Override
    public PageWindow<ProductViewQuery.Listing> findListingPage(ProductViewQuery.Criteria criteria) {
        Query query = entityManager.createNativeQuery(
            "SELECT p.id, p.name, p.price, p.quantity, b.id, b.name " + pickedFrom(criteria));
        query.setParameter("limit", PageWindow.limitOf(criteria.size()));
        query.setParameter("offset", criteria.offset());
        if (criteria.brandId() != null) {
            query.setParameter("brandId", criteria.brandId());
        }

        List<ProductViewQuery.Listing> rows = ((List<?>) query.getResultList()).stream()
            .map(row -> toListing((Object[]) row))
            .toList();

        return PageWindow.of(rows, criteria.size());
    }

    @Override
    public Map<Long, ProductViewQuery.Likes> findLikesOfAlive(List<Long> productIds, Long viewerId) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Query query = entityManager.createNativeQuery("""
            SELECT p.id,
                   (SELECT COUNT(*) FROM product_like l WHERE l.product_id = p.id) AS like_count,
                   EXISTS (SELECT 1 FROM product_like ml WHERE ml.user_id = :viewerId AND ml.product_id = p.id) AS liked
              FROM product p
             WHERE p.id IN (:productIds) AND p.deleted_at IS NULL
            """);
        query.setParameter("productIds", productIds);
        query.setParameter("viewerId", viewerId);

        return ((List<?>) query.getResultList()).stream()
            .map(row -> (Object[]) row)
            .collect(Collectors.toMap(
                row -> ((Number) row[0]).longValue(),
                row -> new ProductViewQuery.Likes(((Number) row[1]).longValue(), ((Number) row[2]).intValue() == 1)));
    }

    private static String pickedPage(ProductViewQuery.Criteria criteria) {
        return VIEW_COLUMNS + pickedFrom(criteria);
    }

    private static String pickedFrom(ProductViewQuery.Criteria criteria) {
        return " FROM (SELECT id FROM product WHERE deleted_at IS NULL "
            + (criteria.brandId() != null ? " AND brand_id = :brandId " : "")
            + " ORDER BY " + orderByOf(criteria.sort(), "")
            + " LIMIT :limit OFFSET :offset) picked "
            + " JOIN product p ON p.id = picked.id "
            + " JOIN brand b ON b.id = p.brand_id "
            + " ORDER BY " + orderByOf(criteria.sort(), "p.");
    }

    private static String likesRankedPage(ProductViewQuery.Criteria criteria) {
        return """
            SELECT p.id, p.name, p.price, p.quantity, b.id, b.name, picked.like_count,
                   EXISTS (SELECT 1 FROM product_like ml WHERE ml.user_id = :viewerId AND ml.product_id = p.id) AS liked
            """
            + " FROM (SELECT id, (SELECT COUNT(*) FROM product_like l WHERE l.product_id = product.id) AS like_count "
            + " FROM product WHERE deleted_at IS NULL "
            + (criteria.brandId() != null ? " AND brand_id = :brandId " : "")
            + " ORDER BY " + orderByOf(criteria.sort(), "")
            + " LIMIT :limit OFFSET :offset) picked "
            + " JOIN product p ON p.id = picked.id "
            + " JOIN brand b ON b.id = p.brand_id "
            + " ORDER BY " + orderByOf(criteria.sort(), "p.");
    }

    private static String orderByOf(ProductViewQuery.Sort sort, String alias) {
        return switch (sort) {
            case LATEST -> alias + "id DESC";
            case PRICE_ASC -> alias + "price ASC, " + alias + "id DESC";
            case LIKES_DESC -> "like_count DESC, " + alias + "id DESC";
        };
    }

    private ProductViewQuery.Listing toListing(Object[] row) {
        return new ProductViewQuery.Listing(
            ((Number) row[0]).longValue(),
            (String) row[1],
            ((Number) row[2]).longValue(),
            ((Number) row[3]).intValue() == 0,
            ((Number) row[4]).longValue(),
            (String) row[5]
        );
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
