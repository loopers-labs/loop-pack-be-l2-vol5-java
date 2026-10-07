package com.loopers.application.product;

import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Product 조회·변경 API 유스케이스의 처리 순서와 트랜잭션 경계를 담당한다. */
@RequiredArgsConstructor
@Component
public class ProductFacade {

    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;
    private final ProductQueryRepository productQueryRepository;
    private final StockHistoryRepository stockHistoryRepository;

    @Transactional(readOnly = true)
    public ProductQueryResult getProduct(Long productId) {
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

    /** 브랜드 관계는 바꾸지 않는다. */
    @Transactional
    public ProductQueryResult update(Long productId, String name, Long price) {
        ProductModel product = findActiveForUpdate(productId);
        product.update(name, price);
        ProductModel updated = productRepository.save(product);
        return findDetail(updated.getId());
    }

    @Transactional
    public void delete(Long productId) {
        ProductModel product = findActiveForUpdate(productId);
        product.delete();
        productRepository.save(product);
    }

    /** finalQuantity는 증감량이 아니라 변경 후의 최종 수량이다. */
    @Transactional
    public ProductModel changeStock(Long productId, Long finalQuantity) {
        ProductModel product = findActiveForUpdate(productId);
        StockChange change = product.changeStock(finalQuantity);

        stockHistoryRepository.save(StockHistoryModel.changedByAdmin(product.getId(), change));
        return productRepository.save(product);
    }

    /**
     * 재고를 바꾸지 않는 수정·삭제도 같은 Product 행 전체를 저장하므로,
     * 주문 확정과 직렬화되도록 첫 조회부터 잠근 현재 상태를 사용한다.
     */
    private ProductModel findActiveForUpdate(Long productId) {
        return productRepository.findActiveForUpdate(productId)
            .orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND));
    }

    private ProductQueryResult findDetail(Long productId) {
        return productQueryRepository.findDetail(productId)
            .orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND));
    }
}
