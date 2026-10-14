package com.loopers.product.adapter.out.persistence;

import com.loopers.product.application.port.out.ProductPort;
import com.loopers.product.domain.ProductModel;
import com.loopers.product.domain.QProductModel;
import com.loopers.support.persistence.QueryDslSort;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ProductPersistenceAdapter implements ProductPort {

    private static final QProductModel PRODUCT = QProductModel.productModel;

    private final ProductJpaRepository productJpaRepository;
    private final JPAQueryFactory queryFactory;

    /**
     * 즉시 flush해 @PrePersist·@PreUpdate(생성·수정 시각)가 응답을 만들기 전에 반영되게 한다.
     */
    @Override
    public ProductModel save(ProductModel product) {
        return productJpaRepository.saveAndFlush(product);
    }

    @Override
    public Optional<ProductModel> findActiveById(Long id) {
        ProductModel product = queryFactory.selectFrom(PRODUCT)
            .where(PRODUCT.id.eq(id), PRODUCT.deletedAt.isNull())
            .fetchOne();
        return Optional.ofNullable(product);
    }

    @Override
    public Optional<ProductModel> findActiveByIdForUpdate(Long id) {
        ProductModel product = queryFactory.selectFrom(PRODUCT)
                .where(PRODUCT.id.eq(id), PRODUCT.deletedAt.isNull())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .fetchOne();

        return Optional.ofNullable(product);
    }

    /**
     * 목록과 개수를 따로 센다. brandId가 null이면 브랜드 조건을 붙이지 않는다.
     * 정렬은 호출자가 넘긴 Sort를 그대로 쓴다 (관리자 목록은 id desc).
     */
    @Override
    public Page<ProductModel> findActive(Long brandId, Pageable pageable) {
        List<ProductModel> content = queryFactory.selectFrom(PRODUCT)
            .where(PRODUCT.deletedAt.isNull(), brandIdEq(brandId))
            .orderBy(QueryDslSort.of(pageable.getSort(), PRODUCT))
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch();

        Long total = queryFactory.select(PRODUCT.count())
            .from(PRODUCT)
            .where(PRODUCT.deletedAt.isNull(), brandIdEq(brandId))
            .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    /**
     * 대상 id를 잠금 없이 고른 뒤, PK 범위로 id 오름차순 잠근다.
     * 브랜드 조건으로 바로 잠그면 InnoDB가 읽는 순서 = 잠그는 순서라, 옵티마이저가 W4 가격순 인덱스를 골라 정렬하는 순간
     * 잠금이 가격순이 된다(실측). 주문 확정(PK id 오름차순)과 순서가 어긋나 ADR-W3-03이 깨진다.
     * 사이에 새 상품이 끼지 않는 이유: 호출자(브랜드 일괄 삭제)가 브랜드 행을 먼저 잠그고, 상품 등록도 그 브랜드 행을 잠근다(R-11).
     */
    @Override
    public List<ProductModel> findActiveByBrandIdForUpdate(Long brandId) {
        List<Long> ids = queryFactory.select(PRODUCT.id)
                .from(PRODUCT)
                .where(PRODUCT.brandId.eq(brandId), PRODUCT.deletedAt.isNull())
                .fetch();
        if (ids.isEmpty()) {
            return List.of();
        }
        return queryFactory.selectFrom(PRODUCT)
                .where(PRODUCT.id.in(ids), PRODUCT.deletedAt.isNull())
                .orderBy(PRODUCT.id.asc())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .fetch();
    }

    @Override
    public List<ProductModel> findAllByIds(Collection<Long> ids) {
        return productJpaRepository.findAllById(ids);
    }

    @Override
    public List<ProductModel> findAllByIdsForUpdate(Collection<Long> ids) {
        return queryFactory.selectFrom(PRODUCT)
                .where(PRODUCT.id.in(ids))
                .orderBy(PRODUCT.id.asc())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .fetch();
    }

    private BooleanExpression brandIdEq(Long brandId) {
        return brandId == null ? null : PRODUCT.brandId.eq(brandId);
    }
}
