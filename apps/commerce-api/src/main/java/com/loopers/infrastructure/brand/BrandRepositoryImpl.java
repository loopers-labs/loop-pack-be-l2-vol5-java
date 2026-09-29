package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.brand.QBrandModel;
import com.loopers.infrastructure.support.QueryDslSort;
import com.querydsl.jpa.impl.JPAQueryFactory;
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
public class BrandRepositoryImpl implements BrandRepository {

    private static final QBrandModel BRAND = QBrandModel.brandModel;

    private final BrandJpaRepository brandJpaRepository;
    private final JPAQueryFactory queryFactory;

    /**
     * 즉시 flush해 @PrePersist·@PreUpdate(생성·수정 시각)가 응답을 만들기 전에 반영되게 한다.
     */
    @Override
    public BrandModel save(BrandModel brand) {
        return brandJpaRepository.saveAndFlush(brand);
    }

    @Override
    public Optional<BrandModel> findActiveById(Long id) {
        BrandModel brand = queryFactory.selectFrom(BRAND)
            .where(BRAND.id.eq(id), BRAND.deletedAt.isNull())
            .fetchOne();
        return Optional.ofNullable(brand);
    }

    /**
     * 목록과 개수를 따로 센다. 정렬은 호출자가 넘긴 Sort를 그대로 쓴다 (관리자 목록은 id desc).
     */
    @Override
    public Page<BrandModel> findActive(Pageable pageable) {
        List<BrandModel> content = queryFactory.selectFrom(BRAND)
            .where(BRAND.deletedAt.isNull())
            .orderBy(QueryDslSort.of(pageable.getSort(), BRAND))
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch();

        Long total = queryFactory.select(BRAND.count())
            .from(BRAND)
            .where(BRAND.deletedAt.isNull())
            .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    @Override
    public List<BrandModel> findAllByIds(Collection<Long> ids) {
        return brandJpaRepository.findAllById(ids);
    }
}
