package com.loopers.infrastructure.product.query;

import com.loopers.application.product.query.ProductQueryRepository;
import com.loopers.application.product.query.ProductSort;
import com.loopers.application.product.query.ProductView;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import com.loopers.domain.product.CatalogRevisionModel;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.loopers.domain.brand.QBrandModel.brandModel;
import static com.loopers.domain.product.QCatalogRevisionModel.catalogRevisionModel;
import static com.loopers.domain.product.QProductModel.productModel;
import static com.loopers.domain.product.QProductStockModel.productStockModel;
import static com.loopers.domain.productlike.QProductStatModel.productStatModel;

/**
 * TB-03 에 TB-02·TB-08 을 조인해 한 쿼리로 읽는다. 셋 다 BC-02 (DR-02, DR-31).
 * 상품의 브랜드는 소프트 삭제만 되므로 brand 는 inner join 이고, 삭제된 브랜드도 그대로 실린다.
 * 좋아요 수는 TB-04 를 세지 않고 TB-08 에서 읽는다. 상품마다 TB-08 행이 하나 있으므로 inner join 이다 (DR-36).
 * 관리자 조회의 재고는 TB-10 에서 읽는다. 상품마다 TB-10 행이 하나 있으므로 inner join 이다 (DR-38).
 */
@RequiredArgsConstructor
@Component
public class ProductQueryRepositoryImpl implements ProductQueryRepository {
    private static final NumberExpression<Long> LIKE_COUNT = productStatModel.likeCount;

    private final JPAQueryFactory queryFactory;
    private final EntityManager entityManager;

    /**
     * 설계 3-7-A: latest = created_at desc / price_asc = price asc / likes_desc = product_stat.like_count desc. 동률은 id desc (ASM-08).
     * brandId 가 있으면 목록과 totalCount 모두 그 브랜드로 거른다 (DR-35). totalCount 는 TB-03 에서 센다.
     */
    @Override
    public PageResult<ProductView.Summary> findActivePage(Long brandId, ProductSort sort, PageQuery query) {
        List<ProductView.Summary> items = findActivePageByPickedIds(brandId, sort, query);
        Long total = queryFactory.select(productModel.count())
            .from(productModel)
            .where(productModel.deletedAt.isNull(), brandIdEq(brandId))
            .fetchOne();
        return PageResult.of(items, query, total == null ? 0 : total);
    }

    @Override
    public Optional<ProductView.Summary> findActive(Long productId) {
        return Optional.ofNullable(selectJoined(summary())
            .where(productModel.id.eq(productId), productModel.deletedAt.isNull())
            .fetchOne());
    }

    @Override
    public Optional<ProductView.DetailState> findDetailState(Long productId) {
        return Optional.ofNullable(queryFactory
            .select(Projections.constructor(ProductView.DetailState.class,
                productModel.revision, brandModel.revision, productStatModel.likeCount))
            .from(productModel)
            .join(brandModel).on(brandModel.id.eq(productModel.brandId))
            .join(productStatModel).on(productStatModel.productId.eq(productModel.id))
            .where(productModel.id.eq(productId), productModel.deletedAt.isNull())
            .fetchOne());
    }

    @Override
    public long findListRevision() {
        Long revision = queryFactory.select(catalogRevisionModel.revision)
            .from(catalogRevisionModel)
            .where(catalogRevisionModel.name.eq(CatalogRevisionModel.PRODUCT_LIST_LATEST))
            .fetchOne();
        if (revision == null) {
            throw new IllegalStateException("[name = " + CatalogRevisionModel.PRODUCT_LIST_LATEST + "] 목록 revision 행이 없습니다.");
        }
        return revision;
    }

    @Override
    public Map<Long, Long> findLikeCounts(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return queryFactory.select(productStatModel.productId, productStatModel.likeCount)
            .from(productStatModel)
            .where(productStatModel.productId.in(productIds))
            .fetch().stream()
            .collect(Collectors.toMap(
                (Tuple t) -> t.get(productStatModel.productId),
                (Tuple t) -> t.get(productStatModel.likeCount)));
    }

    @Override
    public PageResult<ProductView.Admin> findPage(PageQuery query) {
        List<ProductView.Admin> items = selectAdmin()
            .orderBy(productModel.createdAt.desc(), productModel.id.desc())
            .offset(query.offset())
            .limit(query.size())
            .fetch();
        Long total = queryFactory.select(productModel.count())
            .from(productModel)
            .fetchOne();
        return PageResult.of(items, query, total == null ? 0 : total);
    }

    @Override
    public Optional<ProductView.Admin> find(Long productId) {
        return Optional.ofNullable(selectAdmin()
            .where(productModel.id.eq(productId))
            .fetchOne());
    }

    /**
     * 정렬·필터 조건이 한 테이블에 모여 있어 조인 전에 페이지를 정할 수 있다. CTE 에서 정렬 인덱스 순서로 페이지의 상품 id 만 고르고,
     * 그 상품에만 브랜드·좋아요 수를 붙인다. latest·price_asc 는 product 에서, likes_desc 는 product_stat 에서 고른다 (DR-36).
     * Querydsl JPA 는 CTE 를 지원하지 않아 HQL 로 쓴다. 조인을 거치면 CTE 의 순서가 보장되지 않으므로 바깥에서 같은 키로 다시 정렬한다.
     */
    private List<ProductView.Summary> findActivePageByPickedIds(Long brandId, ProductSort sort, PageQuery query) {
        String hql = """
            with page as (
                select c.%s as id
                from %s c
                where c.deletedAt is null%s
                order by %s
                limit :size offset :offset
            )
            select new com.loopers.application.product.query.ProductView$Summary(
                p.id, p.name, p.price, b.id, b.name, s.likeCount)
            from page pg
            join ProductModel p on p.id = pg.id
            join BrandModel b on b.id = p.brandId
            join ProductStatModel s on s.productId = p.id
            order by %s
            """.formatted(
                sort == ProductSort.LIKES_DESC ? "productId" : "id",
                sort == ProductSort.LIKES_DESC ? "ProductStatModel" : "ProductModel",
                brandId == null ? "" : " and c.brandId = :brandId",
                order(sort, "c", "c"),
                order(sort, "p", "s"));
        TypedQuery<ProductView.Summary> typed = entityManager.createQuery(hql, ProductView.Summary.class)
            .setParameter("size", query.size())
            .setParameter("offset", Math.toIntExact(query.offset()));
        if (brandId != null) {
            typed.setParameter("brandId", brandId);
        }
        return typed.getResultList();
    }

    /** CTE 안·밖에서 같은 정렬을 쓰도록 별칭만 바꿔 만든다. 동률은 상품 id desc (ASM-08). product_stat 의 상품 id 는 productId 다. */
    private static String order(ProductSort sort, String product, String stat) {
        return switch (sort) {
            case LATEST -> product + ".createdAt desc, " + product + ".id desc";
            case PRICE_ASC -> product + ".price asc, " + product + ".id desc";
            case LIKES_DESC -> stat + ".likeCount desc, " + stat + ".productId desc";
        };
    }

    /** null 이면 조건 없음. Querydsl where 는 null 조건을 건너뛴다. */
    private static BooleanExpression brandIdEq(Long brandId) {
        return brandId == null ? null : productModel.brandId.eq(brandId);
    }

    private <T> JPAQuery<T> selectJoined(Expression<T> projection) {
        return queryFactory.select(projection)
            .from(productModel)
            .join(brandModel).on(brandModel.id.eq(productModel.brandId))
            .join(productStatModel).on(productStatModel.productId.eq(productModel.id));
    }

    private JPAQuery<ProductView.Admin> selectAdmin() {
        return selectJoined(admin())
            .join(productStockModel).on(productStockModel.productId.eq(productModel.id));
    }

    private static Expression<ProductView.Summary> summary() {
        return Projections.constructor(ProductView.Summary.class,
            productModel.id, productModel.name, productModel.price, brandModel.id, brandModel.name, LIKE_COUNT);
    }

    private static Expression<ProductView.Admin> admin() {
        return Projections.constructor(ProductView.Admin.class,
            productModel.id, productModel.name, productModel.price, productStockModel.available, brandModel.id, brandModel.name, LIKE_COUNT,
            new CaseBuilder().when(productModel.deletedAt.isNotNull()).then(true).otherwise(false));
    }
}
