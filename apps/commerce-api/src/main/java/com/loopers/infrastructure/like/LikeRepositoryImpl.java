package com.loopers.infrastructure.like;

import com.loopers.domain.brand.QBrand;
import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.like.LikedProduct;
import com.loopers.domain.like.QLike;
import com.loopers.domain.product.QProduct;
import com.loopers.domain.product.QProductLikeCount;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
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
    private static final QProduct PRODUCT = QProduct.product;
    private static final QBrand BRAND = QBrand.brand;
    private static final QProductLikeCount LIKE_COUNT = QProductLikeCount.productLikeCount;
    private static final String RELATION_UNIQUE_KEY = "uk_likes_user_product";

    private final LikeJpaRepository likeJpaRepository;
    private final JPAQueryFactory queryFactory;

    /**
     * 같은 관계의 유니크 키 위반만 DuplicateKeyException 으로 구분해 알린다. 다시 실행할 대상을 이것으로만 좁힌다.
     * 다른 무결성 위반은 그대로 둔다 (4주차 설계 3.3)
     */
    @Override
    public Like save(Like like) {
        try {
            return likeJpaRepository.save(like);
        } catch (DataIntegrityViolationException e) {
            if (isRelationDuplicate(e)) {
                throw new DuplicateKeyException("이미 있는 좋아요 관계", e);
            }
            throw e;
        }
    }

    // MySQL 은 제약 이름을 "테이블.제약" 으로 알린다
    private static boolean isRelationDuplicate(DataIntegrityViolationException e) {
        return e.getCause() instanceof ConstraintViolationException violation
            && violation.getConstraintName() != null
            && violation.getConstraintName().endsWith(RELATION_UNIQUE_KEY);
    }

    @Override
    public Optional<Like> find(Long userId, Long productId) {
        return likeJpaRepository.findByUserIdAndProductId(userId, productId);
    }

    @Override
    public int delete(Long userId, Long productId) {
        return likeJpaRepository.deleteRelation(userId, productId);
    }

    // 삭제된 상품은 내 목록에서 제외한다. 관계는 남아 있어 취소할 수 있다 (설계 6.3)
    @Override
    public Page<LikedProduct> findLikedProducts(Long userId, Pageable pageable) {
        List<LikedProduct> content = queryFactory
            .select(Projections.constructor(LikedProduct.class,
                PRODUCT.id, PRODUCT.name, PRODUCT.price, PRODUCT.stock,
                LIKE_COUNT.likeCount,
                BRAND.id, BRAND.name, LIKE.createdAt))
            .from(LIKE)
            .join(PRODUCT).on(PRODUCT.id.eq(LIKE.productId))
            .join(PRODUCT.brand, BRAND)
            .join(LIKE_COUNT).on(LIKE_COUNT.productId.eq(PRODUCT.id))
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
