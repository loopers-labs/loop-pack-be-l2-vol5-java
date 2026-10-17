package com.loopers.infrastructure.product;

import com.loopers.domain.like.ProductLikeCountRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class ProductRepositoryImpl implements ProductRepository {
    private final ProductJpaRepository productJpaRepository;
    // 브랜드 없는 likes_desc 의 1단계(페이지의 상품 id)를 읽는다. 다른 인프라가 아니라 도메인 인터페이스에 의존한다.
    private final ProductLikeCountRepository productLikeCountRepository;

    @Override
    public Product save(Product product) {
        return productJpaRepository.save(product);
    }

    @Override
    public Optional<Product> findActive(Long id) {
        return productJpaRepository.findByIdAndDeletedAtIsNull(id);
    }

    @Override
    public List<Product> findAllActiveByIds(Collection<Long> ids) {
        // 빈 목록으로 IN 조회를 하면 DB 에 따라 문법 오류가 나므로 조회하지 않는다.
        if (ids.isEmpty()) {
            return List.of();
        }
        return productJpaRepository.findAllByIdInAndDeletedAtIsNull(ids);
    }

    @Override
    public Page<Product> findAllActive(Pageable pageable) {
        return productJpaRepository.findAllByDeletedAtIsNullOrderByIdDesc(pageable);
    }

    @Override
    public Page<Product> findAllActive(Long brandId, ProductSort sort, Pageable pageable) {
        return switch (sort) {
            case LATEST -> productJpaRepository.findAllActiveOrderByLatest(brandId, pageable);
            case PRICE_ASC -> productJpaRepository.findAllActiveOrderByPriceAsc(brandId, pageable);
            case LIKES_DESC -> brandId == null
                ? findAllActiveOrderByLikesDesc(pageable)
                : productJpaRepository.findAllActiveOrderByLikesDescOfBrand(brandId, pageable);
        };
    }

    // 브랜드 없는 likes_desc: ① 집계 테이블에서 페이지의 상품 id 를 읽고 ② 그 id 로 상품을 조회한다. 집계는 노출 상품만
    // 행을 가지므로 ①은 삭제 상품을 거르지 않고 인덱스 앞부분 (offset + size)행만 읽는다.
    // ②의 삭제 확인과 건너뛰기는 방어다. 상품 삭제와 행 삭제가 같은 TX 라 앱 경로로는 삭제 상품의 행이 남지 않지만,
    // DB 를 직접 고쳐 행이 남아도 삭제 상품이 노출되지 않게 한다(그때는 그 페이지가 짧아진다).
    private Page<Product> findAllActiveOrderByLikesDesc(Pageable pageable) {
        List<Long> ids = productLikeCountRepository.findRankedProductIds(pageable);
        long total = productJpaRepository.countActive();
        if (ids.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, total);
        }
        Map<Long, Product> found = productJpaRepository.findAllByIdInAndDeletedAtIsNull(ids).stream()
            .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<Product> ordered = ids.stream().map(found::get).filter(Objects::nonNull).toList();
        return new PageImpl<>(ordered, pageable, total);
    }

    @Override
    public Page<Product> findAllActiveLikedBy(Long userId, Pageable pageable) {
        return productJpaRepository.findAllActiveLikedBy(userId, pageable);
    }

    @Override
    public Optional<Product> findActiveForUpdate(Long id) {
        return productJpaRepository.findActiveForUpdate(id);
    }

    @Override
    public List<Product> findAllActiveByIdsForUpdate(Collection<Long> ids) {
        // 빈 목록으로 IN 조회를 하면 DB 에 따라 문법 오류가 나므로 조회하지 않는다.
        if (ids.isEmpty()) {
            return List.of();
        }
        return productJpaRepository.findAllActiveByIdsForUpdate(ids);
    }

    @Override
    public List<Product> findAllActiveByBrandIdForUpdate(Long brandId) {
        return productJpaRepository.findAllActiveByBrandIdForUpdate(brandId);
    }
}
