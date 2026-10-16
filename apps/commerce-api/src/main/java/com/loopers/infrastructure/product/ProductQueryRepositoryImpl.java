package com.loopers.infrastructure.product;

import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductQueryRepository;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.ProductSummary;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Repository
@Transactional(readOnly = true)
public class ProductQueryRepositoryImpl implements ProductQueryRepository {

    private static final String PROJECTION = """
        select new com.loopers.domain.product.ProductSummary(
            p.id, p.name, p.price, p.stock.quantity, b.id, b.name, count(l.id),
            p.createdAt, p.updatedAt, p.deletedAt)
        """;
    private static final String PAGE_PROJECTION = """
        select new com.loopers.domain.product.ProductSummary(
            p.id, p.name, p.price, p.stock.quantity, b.id, b.name,
            (select count(l.id) from ProductLike l where l.product.id = p.id),
            p.createdAt, p.updatedAt, p.deletedAt)
        """;
    private static final String GLOBAL_PAGE_PROJECTION = """
        select new com.loopers.infrastructure.product.ProductPageRow(
            p.id, p.name, p.price, p.stock.quantity, b.id, b.name,
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
        if (!includeDeleted && brandId == null && sort == ProductSort.LIKES_DESC) {
            return readGlobalLikesPage(page, size);
        }
        if (!includeDeleted && sort != ProductSort.LIKES_DESC) {
            String from = PRODUCT_FROM + " where " + predicate;
            if (brandId == null) {
                // 무필터 조회는 정렬 전에 전체 상품의 상관 집계가 실행될 수 있어 페이지를 먼저 확정한다.
                return readGlobalPage(from, order, page, size);
            }
            // 가격/최신순은 좋아요 집계가 페이지 선정에 영향을 주지 않는다.
            // 브랜드 필터 경로는 기존 페이지 projection과 인덱스 접근을 유지한다.
            return readPage(PAGE_PROJECTION + from + " order by " + order, from, parameters, page, size);
        }
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
        return readPage(PROJECTION + PRODUCT_FROM + LIKE_JOIN + extraJoin + where + GROUP_BY + extraGroup
            + " order by " + order, PRODUCT_FROM + extraJoin + where, parameters, page, size);
    }

    private PageResult<ProductSummary> readPage(String selection, String countFrom, Map<String, Object> parameters,
                                                int page, int size) {
        long total = query("select count(p.id)" + countFrom, Long.class, parameters).getSingleResult();
        long offset = (long) page * size;
        if (offset >= total) {
            return PageResult.of(List.of(), page, size, total);
        }
        List<ProductSummary> items = query(selection, ProductSummary.class, parameters)
            .setFirstResult(Math.toIntExact(offset)).setMaxResults(size).getResultList();
        return PageResult.of(items, page, size, total);
    }

    private PageResult<ProductSummary> readGlobalPage(String from, String order, int page, int size) {
        long total = query("select count(p.id)" + from, Long.class, Map.of()).getSingleResult();
        long offset = (long) page * size;
        if (offset >= total) {
            return PageResult.of(List.of(), page, size, total);
        }
        List<ProductPageRow> rows = query(GLOBAL_PAGE_PROJECTION + from + " order by " + order,
            ProductPageRow.class, Map.of()).setFirstResult(Math.toIntExact(offset)).setMaxResults(size).getResultList();
        if (rows.isEmpty()) {
            return PageResult.of(List.of(), page, size, total);
        }
        List<Long> ids = rows.stream().map(ProductPageRow::productId).toList();
        List<Tuple> counts = query("""
            select l.product.id as productId, count(l.id) as likeCount
            from ProductLike l where l.product.id in :productIds group by l.product.id
            """, Tuple.class, Map.of("productIds", ids)).getResultList();
        Map<Long, Long> likesByProduct = new HashMap<>();
        counts.forEach(count -> likesByProduct.put(count.get("productId", Long.class), count.get("likeCount", Long.class)));
        // 집계 SQL의 반환 순서와 무관하게 원래 페이지 순서를 보존하며, 좋아요 관계가 없으면 0이다.
        List<ProductSummary> items = rows.stream()
            .map(row -> row.toSummary(likesByProduct.getOrDefault(row.productId(), 0L))).toList();
        return PageResult.of(items, page, size, total);
    }

    private PageResult<ProductSummary> readGlobalLikesPage(int page, int size) {
        String where = " where " + ACTIVE;
        long total = query("select count(p.id)" + PRODUCT_FROM + where, Long.class, Map.of()).getSingleResult();
        long offset = (long) page * size;
        if (offset >= total) {
            return PageResult.of(List.of(), page, size, total);
        }
        // 상세 필드를 집계에 싣지 않고 ID와 좋아요 수만으로 순위·페이지를 확정한다.
        List<Tuple> ranks = query("select p.id as productId, count(l.id) as likeCount"
            + PRODUCT_FROM + LIKE_JOIN + where + " group by p.id order by count(l.id) desc, p.id desc",
            Tuple.class, Map.of()).setFirstResult(Math.toIntExact(offset)).setMaxResults(size).getResultList();
        if (ranks.isEmpty()) {
            return PageResult.of(List.of(), page, size, total);
        }
        List<Long> ids = ranks.stream().map(rank -> rank.get("productId", Long.class)).toList();
        List<ProductPageRow> rows = query(GLOBAL_PAGE_PROJECTION + PRODUCT_FROM + where + " and p.id in :productIds",
            ProductPageRow.class, Map.of("productIds", ids)).getResultList();
        Map<Long, ProductPageRow> rowsById = new HashMap<>();
        rows.forEach(row -> rowsById.put(row.productId(), row));
        // IN 조회 순서는 보장되지 않으므로 순위 결과 순서로 조립하고, 같은 스냅샷에서 얻은 집계값을 사용한다.
        List<ProductSummary> items = ranks.stream().map(rank -> {
            ProductPageRow row = Objects.requireNonNull(rowsById.get(rank.get("productId", Long.class)),
                "Ranked product is missing from the same read snapshot");
            return row.toSummary(rank.get("likeCount", Long.class));
        }).toList();
        return PageResult.of(items, page, size, total);
    }

    private <T> TypedQuery<T> query(String jpql, Class<T> resultType, Map<String, Object> parameters) {
        TypedQuery<T> query = entityManager.createQuery(jpql, resultType);
        parameters.forEach(query::setParameter);
        return query;
    }
}
