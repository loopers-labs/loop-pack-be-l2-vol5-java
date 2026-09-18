package com.loopers.infrastructure.like;

import com.loopers.domain.brand.QBrand;
import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.like.LikedProduct;
import com.loopers.domain.like.QLike;
import com.loopers.domain.product.QProduct;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class LikeRepositoryImpl implements LikeRepository {

    private static final QLike LIKE = QLike.like;
    private static final QLike OTHER_LIKE = new QLike("otherLike");
    private static final QProduct PRODUCT = QProduct.product;
    private static final QBrand BRAND = QBrand.brand;

    private final LikeJpaRepository likeJpaRepository;
    private final JPAQueryFactory queryFactory;

    @Override
    public Like save(Like like) {
        return likeJpaRepository.save(like);
    }

    @Override
    public Optional<Like> find(Long userId, Long productId) {
        return likeJpaRepository.findByUserIdAndProductId(userId, productId);
    }

    @Override
    public void delete(Like like) {
        likeJpaRepository.delete(like);
    }

    @Override
    public long countByProductId(Long productId) {
        return likeJpaRepository.countByProductId(productId);
    }

    // 삭제된 상품은 내 목록에서 제외한다. 관계는 남아 있어 취소할 수 있다 (설계 6.3)
    @Override
    public Page<LikedProduct> findLikedProducts(Long userId, Pageable pageable) {
        List<LikedProduct> content = queryFactory
            .select(Projections.constructor(LikedProduct.class,
                PRODUCT.id, PRODUCT.name, PRODUCT.price, PRODUCT.stock,
                JPAExpressions.select(OTHER_LIKE.count()).from(OTHER_LIKE).where(OTHER_LIKE.productId.eq(PRODUCT.id)),
                BRAND.id, BRAND.name, LIKE.createdAt))
            .from(LIKE)
            .join(PRODUCT).on(PRODUCT.id.eq(LIKE.productId))
            .join(PRODUCT.brand, BRAND)
            .where(LIKE.userId.eq(userId), PRODUCT.deletedAt.isNull())
            .orderBy(LIKE.createdAt.desc(), LIKE.id.desc())
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch();
        Long total = queryFactory.select(LIKE.count())
            .from(LIKE)
            .join(PRODUCT).on(PRODUCT.id.eq(LIKE.productId))
            .where(LIKE.userId.eq(userId), PRODUCT.deletedAt.isNull())
            .fetchOne();
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }
}
