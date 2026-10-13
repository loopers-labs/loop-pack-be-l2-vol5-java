package com.loopers.infrastructure.productlike;

import com.loopers.domain.productlike.ProductLikeModel;
import com.loopers.domain.productlike.ProductLikeRepository;
import com.loopers.domain.productlike.ProductStatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class ProductLikeRepositoryImpl implements ProductLikeRepository {
    private final ProductLikeJpaRepository productLikeJpaRepository;
    private final ProductStatJpaRepository productStatJpaRepository;

    @Override
    public ProductLikeModel save(ProductLikeModel like) {
        return productLikeJpaRepository.save(like);
    }

    @Override
    public Optional<ProductLikeModel> find(Long userId, Long productId) {
        return productLikeJpaRepository.findByUserIdAndProductId(userId, productId);
    }

    @Override
    public int delete(Long userId, Long productId) {
        return productLikeJpaRepository.deleteByUserIdAndProductId(userId, productId);
    }

    @Override
    public ProductStatModel saveStat(ProductStatModel stat) {
        return productStatJpaRepository.save(stat);
    }

    @Override
    public Optional<ProductStatModel> findStat(Long productId) {
        return productStatJpaRepository.findByProductId(productId);
    }

    @Override
    public void increaseLikeCount(Long productId) {
        requireOneRow(productStatJpaRepository.increaseLikeCount(productId, ZonedDateTime.now()), productId);
    }

    @Override
    public void decreaseLikeCount(Long productId) {
        requireOneRow(productStatJpaRepository.decreaseLikeCount(productId, ZonedDateTime.now()), productId);
    }

    @Override
    public void deleteStat(Long productId) {
        productStatJpaRepository.markDeleted(productId, ZonedDateTime.now());
    }

    @Override
    public void deleteStatsOfBrand(Long brandId) {
        productStatJpaRepository.markDeletedByBrandId(brandId, ZonedDateTime.now());
    }

    @Override
    public Map<Long, Long> findLikeCounts(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return productStatJpaRepository.findAllByProductIdIn(productIds).stream()
            .collect(Collectors.toMap(ProductStatModel::getProductId, ProductStatModel::getLikeCount));
    }

    /** 상품마다 행이 하나 있어야 한다 (DR-36). 없으면 좋아요 수가 관계 수와 어긋난 채 커밋되지 않게 실패시킨다. */
    private static void requireOneRow(int updated, Long productId) {
        if (updated != 1) {
            throw new IllegalStateException("[productId = " + productId + "] 좋아요 수 행이 없습니다.");
        }
    }
}
