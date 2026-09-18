package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductAdminQuery;
import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageWindow;
import com.loopers.domain.common.PageSize;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class ProductAdminQueryImpl implements ProductAdminQuery {

    private final EntityManager entityManager;

    private static String orderByOf(ProductAdminQuery.Sort sort) {
        return switch (sort) {
            case LATEST -> "p.id DESC";
            case PRICE_ASC -> "p.price ASC, p.id DESC";
            case PRICE_DESC -> "p.price DESC, p.id DESC";
            case STOCK_ASC -> "p.quantity ASC, p.id DESC";
        };
    }

    @Override
    public PageWindow<ProductAdminQuery.View> findPage(
        Long brandId, ProductAdminQuery.Sort sort, PageNumber page, PageSize size) {
        String where = " WHERE p.deleted_at IS NULL "
            + (brandId != null ? " AND p.brand_id = :brandId " : "");

        Query query = entityManager.createNativeQuery("""
            SELECT p.id, p.brand_id, b.name, p.name, p.price, p.quantity
              FROM product p
              JOIN brand b ON b.id = p.brand_id
            """ + where + " ORDER BY " + orderByOf(sort) + " LIMIT :limit OFFSET :offset");
        query.setParameter("limit", PageWindow.limitOf(size));
        query.setParameter("offset", page.offsetWith(size));
        if (brandId != null) {
            query.setParameter("brandId", brandId);
        }

        List<ProductAdminQuery.View> rows = ((List<?>) query.getResultList()).stream()
            .map(row -> (Object[]) row)
            .map(row -> new ProductAdminQuery.View(
                ((Number) row[0]).longValue(),
                ((Number) row[1]).longValue(),
                (String) row[2],
                (String) row[3],
                ((Number) row[4]).longValue(),
                ((Number) row[5]).intValue()))
            .toList();

        return PageWindow.of(rows, size);
    }
}
