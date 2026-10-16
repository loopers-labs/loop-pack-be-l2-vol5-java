package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.PageRequest;
import jakarta.persistence.EntityManager;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ProductRepositoryImpl implements ProductRepository {

    // 정렬 기준(좋아요 수)이 like 도메인의 카운터 테이블에 있어 SQL에서만 조인한다. 카운터가 없는 상품은
    // 0으로 순위에 두고, 반환된 페이지의 카운터 누락은 기존처럼 좋아요 수 조회에서 오류로 드러난다.
    // JPA native query로 실행해, 같은 트랜잭션에서 아직 flush되지 않은 상품 변경도 순위에 반영한다.
    private static final String LIKES_DESC_IDS = "select p.id from product p "
        + "left join product_like_count lc on lc.product_id = p.id "
        + "where p.deleted_at is null %s"
        + "and exists (select 1 from brand b where b.id = p.brand_id and b.deleted_at is null) "
        + "order by coalesce(lc.like_count, 0) desc, p.created_at desc, p.id desc "
        + "limit :size offset :offset";

    private final ProductJpaRepository productJpaRepository;
    private final EntityManager entityManager;

    @Override
    public Optional<Long> findBrandId(Long productId) { return productJpaRepository.findBrandId(productId); }

    @Override
    public ProductModel save(ProductModel product) {
        return productJpaRepository.save(product);
    }

    @Override
    public Optional<ProductModel> find(Long id) {
        return productJpaRepository.findById(id);
    }

    @Override
    public Optional<ProductModel> findForUpdate(Long id) {
        return productJpaRepository.findForUpdate(id);
    }

    @Override
    public List<ProductModel> findAllActive() {
        return productJpaRepository.findByDeletedAtIsNull();
    }

    @Override
    public List<ProductModel> findActiveByBrandId(Long brandId) {
        return productJpaRepository.findByBrandIdAndDeletedAtIsNull(brandId);
    }

    @Override
    public List<Long> findVisibleIdsByLikesDesc(Long brandId, int page, int size) {
        if ((long) page * size > Integer.MAX_VALUE) {
            return List.of();
        }
        var query = entityManager.createNativeQuery(
                LIKES_DESC_IDS.formatted(brandId == null ? "" : "and p.brand_id = :brandId "))
            .setParameter("size", size)
            .setParameter("offset", page * size);
        if (brandId != null) {
            query.setParameter("brandId", brandId);
        }
        List<?> ids = query.getResultList();
        return ids.stream().map(id -> ((Number) id).longValue()).toList();
    }

    @Override
    public List<ProductModel> findActiveByPriceAsc(Long brandId, int page, int size) {
        if ((long) page * size > Integer.MAX_VALUE) {
            return List.of();
        }
        return productJpaRepository.findVisibleByPriceAsc(brandId, PageRequest.of(page, size));
    }

    @Override
    public List<ProductModel> findActiveByIds(Collection<Long> ids) {
        return productJpaRepository.findByIdInAndDeletedAtIsNull(ids);
    }

    @Override
    public List<Long> findActiveIds(Long brandId, Collection<Long> ids) {
        return productJpaRepository.findActiveIds(brandId, ids);
    }

    @Override
    public List<ProductModel> findActiveByBrandIdForUpdate(Long brandId) {
        // BrandFacade holds the brand write lock before this transaction's first snapshot read.
        // Registration cannot add new products until deletion commits; brandId is immutable.
        return productJpaRepository.findActiveIdsByBrandId(brandId).stream()
            .map(productJpaRepository::findForUpdate)
            .flatMap(Optional::stream)
            .filter(product -> product.getDeletedAt() == null)
            .toList();
    }
}
