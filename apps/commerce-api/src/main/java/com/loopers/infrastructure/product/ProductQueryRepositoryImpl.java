package com.loopers.infrastructure.product;

import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductQueryRepository;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.ProductSummary;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@Transactional(readOnly = true)
public class ProductQueryRepositoryImpl implements ProductQueryRepository {

    private static final String PROJECTION = """
        select new com.loopers.domain.product.ProductSummary(
            p.id, p.name, p.price, p.stock.quantity, b.id, b.name, count(l.id),
            p.createdAt, p.updatedAt, p.deletedAt)
        """;
    private static final String PRODUCT_FROM = " from Product p join p.brand b ";
    private static final String LIKE_JOIN = " left join ProductLike l on l.product.id = p.id ";
    private static final String OWN_LIKE_JOIN = " join ProductLike own on own.product.id = p.id ";
    private static final String ACTIVE = "p.deletedAt is null and b.deletedAt is null";
    private static final String GROUP_BY = """
         group by p.id, p.name, p.price, p.stock.quantity, b.id, b.name,
            p.createdAt, p.updatedAt, p.deletedAt
        """;

    private final EntityManager entityManager;

    public ProductQueryRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<ProductSummary> findDetail(long productId, boolean includeDeleted) {
        String where = " where p.id = :productId" + (includeDeleted ? "" : " and " + ACTIVE);
        return query(PROJECTION + PRODUCT_FROM + LIKE_JOIN + where + GROUP_BY,
            ProductSummary.class, Map.of("productId", productId)).getResultList().stream().findFirst();
    }

    @Override
    public PageResult<ProductSummary> findPage(Long brandId, int page, int size, ProductSort sort, boolean includeDeleted) {
        Map<String, Object> parameters = new HashMap<>();
        String predicate = includeDeleted ? "1 = 1" : ACTIVE;
        if (brandId != null) {
            predicate += " and b.id = :brandId";
            parameters.put("brandId", brandId);
        }
        String order = switch (sort) {
            case LATEST -> "p.createdAt desc, p.id desc";
            case PRICE_ASC -> "p.price asc, p.id desc";
            case LIKES_DESC -> "count(l.id) desc, p.id desc";
        };
        return page("", predicate, parameters, "", order, page, size);
    }

    @Override
    public PageResult<ProductSummary> findLikedPage(long userId, int page, int size) {
        return page(OWN_LIKE_JOIN, ACTIVE + " and own.user.id = :userId", Map.of("userId", userId),
            ", own.createdAt, own.id ", "own.createdAt desc, own.id desc", page, size);
    }

    private PageResult<ProductSummary> page(String extraJoin, String predicate, Map<String, Object> parameters,
                                            String extraGroup, String order, int page, int size) {
        String where = " where " + predicate;
        long total = query("select count(p.id)" + PRODUCT_FROM + extraJoin + where,
            Long.class, parameters).getSingleResult();
        long offset = (long) page * size;
        if (offset >= total) {
            return PageResult.of(List.of(), page, size, total);
        }
        List<ProductSummary> items = query(PROJECTION + PRODUCT_FROM + LIKE_JOIN + extraJoin
            + where + GROUP_BY + extraGroup + " order by " + order, ProductSummary.class, parameters)
            .setFirstResult(Math.toIntExact(offset)).setMaxResults(size).getResultList();
        return PageResult.of(items, page, size, total);
    }

    private <T> TypedQuery<T> query(String jpql, Class<T> resultType, Map<String, Object> parameters) {
        TypedQuery<T> query = entityManager.createQuery(jpql, resultType);
        parameters.forEach(query::setParameter);
        return query;
    }
}
