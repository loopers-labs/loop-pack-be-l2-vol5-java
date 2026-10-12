package com.loopers.infrastructure.product.query;

import com.loopers.application.product.query.ProductQueryRepository;
import com.loopers.application.product.query.ProductSort;
import com.loopers.application.product.query.ProductView;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
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

import java.util.List;
import java.util.Optional;

import static com.loopers.domain.brand.QBrandModel.brandModel;
import static com.loopers.domain.product.QProductModel.productModel;
import static com.loopers.domain.productlike.QProductLikeModel.productLikeModel;

/**
 * TB-03 에 TB-02·TB-04 를 조인+집계해 한 쿼리로 읽는다. 셋 다 BC-02 (DR-02, DR-31).
 * 상품의 브랜드는 소프트 삭제만 되므로 brand 는 inner join 이고, 삭제된 브랜드도 그대로 실린다.
 */
@RequiredArgsConstructor
@Component
public class ProductQueryRepositoryImpl implements ProductQueryRepository {
    private static final NumberExpression<Long> LIKE_COUNT = productLikeModel.id.count();

    private final JPAQueryFactory queryFactory;
    private final EntityManager entityManager;

    /**
     * 설계 3-7-A: latest = created_at desc / price_asc = price asc / likes_desc = count(product_like) desc. 동률은 id desc (ASM-08).
     * brandId 가 있으면 목록과 totalCount 모두 그 브랜드로 거른다 (DR-35).
     */
    @Override
    public PageResult<ProductView.Summary> findActivePage(Long brandId, ProductSort sort, PageQuery query) {
        List<ProductView.Summary> items = switch (sort) {
            case LATEST, PRICE_ASC -> findActivePageByProductOrder(brandId, sort, query);
            case LIKES_DESC -> selectJoined(summary())
                .where(productModel.deletedAt.isNull(), brandIdEq(brandId))
                .orderBy(LIKE_COUNT.desc(), productModel.id.desc())
                .offset(query.offset())
                .limit(query.size())
                .fetch();
        };
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
    public PageResult<ProductView.Admin> findPage(PageQuery query) {
        List<ProductView.Admin> items = selectJoined(admin())
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
        return Optional.ofNullable(selectJoined(admin())
            .where(productModel.id.eq(productId))
            .fetchOne());
    }

    /**
     * 정렬 키가 product 컬럼뿐이라 집계 전에 페이지를 정할 수 있다. CTE 에서 정렬 인덱스 순서로 페이지의 상품 id 만 고르고,
     * 그 상품에만 브랜드 조인·좋아요 집계를 한다. Querydsl JPA 는 CTE 를 지원하지 않아 HQL 로 쓴다.
     * 조인을 거치면 CTE 의 순서가 보장되지 않으므로 바깥에서 같은 키로 다시 정렬한다.
     */
    private List<ProductView.Summary> findActivePageByProductOrder(Long brandId, ProductSort sort, PageQuery query) {
        String hql = """
            with page as (
                select ip.id as id
                from ProductModel ip
                where ip.deletedAt is null%s
                order by %s
                limit :size offset :offset
            )
            select new com.loopers.application.product.query.ProductView$Summary(
                p.id, p.name, p.price, b.id, b.name, count(pl.id))
            from page pg
            join ProductModel p on p.id = pg.id
            join BrandModel b on b.id = p.brandId
            left join ProductLikeModel pl on pl.productId = p.id
            group by p.id, b.id
            order by %s
            """.formatted(brandId == null ? "" : " and ip.brandId = :brandId", productOrder(sort, "ip"), productOrder(sort, "p"));
        TypedQuery<ProductView.Summary> typed = entityManager.createQuery(hql, ProductView.Summary.class)
            .setParameter("size", query.size())
            .setParameter("offset", Math.toIntExact(query.offset()));
        if (brandId != null) {
            typed.setParameter("brandId", brandId);
        }
        return typed.getResultList();
    }

    /** CTE 안·밖에서 같은 정렬을 쓰도록 별칭만 바꿔 만든다. 동률은 id desc (ASM-08). */
    private static String productOrder(ProductSort sort, String alias) {
        String primary = switch (sort) {
            case LATEST -> alias + ".createdAt desc";
            case PRICE_ASC -> alias + ".price asc";
            case LIKES_DESC -> throw new IllegalArgumentException("likes_desc 는 상품 컬럼으로 정렬할 수 없습니다.");
        };
        return primary + ", " + alias + ".id desc";
    }

    /** null 이면 조건 없음. Querydsl where 는 null 조건을 건너뛴다. */
    private static BooleanExpression brandIdEq(Long brandId) {
        return brandId == null ? null : productModel.brandId.eq(brandId);
    }

    private <T> JPAQuery<T> selectJoined(Expression<T> projection) {
        return queryFactory.select(projection)
            .from(productModel)
            .join(brandModel).on(brandModel.id.eq(productModel.brandId))
            .leftJoin(productLikeModel).on(productLikeModel.productId.eq(productModel.id))
            .groupBy(productModel.id, brandModel.id);
    }

    private static Expression<ProductView.Summary> summary() {
        return Projections.constructor(ProductView.Summary.class,
            productModel.id, productModel.name, productModel.price, brandModel.id, brandModel.name, LIKE_COUNT);
    }

    private static Expression<ProductView.Admin> admin() {
        return Projections.constructor(ProductView.Admin.class,
            productModel.id, productModel.name, productModel.price, productModel.stock, brandModel.id, brandModel.name, LIKE_COUNT,
            new CaseBuilder().when(productModel.deletedAt.isNotNull()).then(true).otherwise(false));
    }
}
