package com.loopers.application.product;

import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductDetailCache;
import com.loopers.domain.product.ProductDetailData;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductQueryRepository;
import com.loopers.domain.product.ProductQueryResult;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.StockChange;
import com.loopers.domain.product.StockHistoryModel;
import com.loopers.domain.product.StockHistoryRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.transaction.AfterCommitRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Product 조회·변경 API 유스케이스의 처리 순서와 트랜잭션 경계를 담당한다. */
@RequiredArgsConstructor
@Component
public class ProductFacade {

    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;
    private final ProductQueryRepository productQueryRepository;
    private final StockHistoryRepository stockHistoryRepository;
    private final ProductDetailCache productDetailCache;
    private final AfterCommitRunner afterCommitRunner;

    /**
     * 고객 상세. 기본정보는 캐시에서, 상품·브랜드 노출 확인과 좋아요 수는 매번 DB 에서 읽어 합성한다.
     * hit 는 기본정보 열을 읽지 않는 확인 조회 한 번, miss 는 완성 상세 조회 한 번으로 응답하고 그 값을 채운다.
     * 캐시 대기 동안 DB 연결을 잡지 않도록 트랜잭션을 열지 않으며, 각 경로의 DB 조회는 한 문장이다.
     */
    public ProductQueryResult getProduct(Long productId) {
        if (!productDetailCache.isEnabled()) {
            return findDetail(productId);
        }

        Optional<ProductDetailData.CacheValue> cached = productDetailCache.find(productId);
        if (cached.isEmpty()) {
            ProductQueryResult detail = findDetail(productId);
            productDetailCache.put(productId, ProductDetailData.CacheValue.from(detail));
            return detail;
        }

        ProductDetailData.DbResult dbResult = productQueryRepository.findVisibleWithLikeCount(productId)
            .orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND));
        ProductDetailData.CacheValue cacheValue = cached.get();
        return new ProductQueryResult(
            dbResult.id(),
            dbResult.brandId(),
            cacheValue.brandName(),
            cacheValue.name(),
            cacheValue.price(),
            dbResult.likeCount(),
            cacheValue.stockQuantity()
        );
    }

    /** 관리자 상세는 고객 캐시를 거치지 않고 DB 에서 읽는다. */
    @Transactional(readOnly = true)
    public ProductQueryResult getProductForAdmin(Long productId) {
        return findDetail(productId);
    }

    @Transactional(readOnly = true)
    public PageResult<ProductQueryResult> getProducts(Long brandId, PageCommand page, ProductSort sort) {
        return productQueryRepository.findPage(brandId, page, sort);
    }

    /** 관리자 목록은 활성 상품을 등록 시각 기준으로 조회한다. */
    @Transactional(readOnly = true)
    public PageResult<ProductQueryResult> getAllProducts(PageCommand page, ListSort sort) {
        return productQueryRepository.findAllPage(page, sort);
    }

    /** Brand의 존재와 삭제 여부를 확인하지만 Brand는 변경하지 않는다. */
    @Transactional
    public ProductQueryResult create(Long brandId, String name, Long price) {
        brandRepository.findActive(brandId)
            .orElseThrow(() -> new CoreException(ErrorType.BRAND_NOT_FOUND));

        ProductModel created = productRepository.save(ProductModel.create(brandId, name, price));
        return findDetail(created.getId());
    }

    /** 브랜드 관계는 바꾸지 않는다. 응답은 이 트랜잭션의 DB 조회이며 고객 공용 캐시에 채우지 않는다. */
    @Transactional
    public ProductQueryResult update(Long productId, String name, Long price) {
        ProductModel product = findActiveForUpdate(productId);
        product.update(name, price);
        ProductModel updated = productRepository.save(product);
        evictDetailAfterCommit(List.of(updated.getId()));
        return findDetail(updated.getId());
    }

    @Transactional
    public void delete(Long productId) {
        ProductModel product = findActiveForUpdate(productId);
        product.delete();
        productRepository.save(product);
        evictDetailAfterCommit(List.of(product.getId()));
    }

    /** finalQuantity는 증감량이 아니라 변경 후의 최종 수량이다. */
    @Transactional
    public ProductModel changeStock(Long productId, Long finalQuantity) {
        ProductModel product = findActiveForUpdate(productId);
        StockChange change = product.changeStock(finalQuantity);

        stockHistoryRepository.save(StockHistoryModel.changedByAdmin(product.getId(), change));
        ProductModel changed = productRepository.save(product);
        evictDetailAfterCommit(List.of(changed.getId()));
        return changed;
    }

    /**
     * 재고를 바꾸지 않는 수정·삭제도 같은 Product 행 전체를 저장하므로,
     * 주문 확정과 직렬화되도록 첫 조회부터 잠근 현재 상태를 사용한다.
     */
    private ProductModel findActiveForUpdate(Long productId) {
        return productRepository.findActiveForUpdate(productId)
            .orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND));
    }

    private void evictDetailAfterCommit(List<Long> productIds) {
        afterCommitRunner.runAfterCommit("상품 상세 기본정보 무효화", () -> productDetailCache.evictAll(productIds));
    }

    private ProductQueryResult findDetail(Long productId) {
        return productQueryRepository.findDetail(productId)
            .orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND));
    }
}
