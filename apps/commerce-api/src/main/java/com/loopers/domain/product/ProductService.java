package com.loopers.domain.product;

import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 조회와 상품·재고 변경을 담당한다.
 * 목록·상세 조회는 QueryRepository 가 반환한 결과를 그대로 조회 계약으로 사용한다.
 */
@RequiredArgsConstructor
@Component
public class ProductService {

    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;
    private final ProductQueryRepository productQueryRepository;
    private final StockHistoryRepository stockHistoryRepository;

    @Transactional(readOnly = true)
    public ProductQueryResult getProduct(Long productId) {
        return productQueryRepository.findDetail(productId)
            .orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND));
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

    /** Brand 의 존재와 삭제 여부를 읽어 확인하지만 Brand 를 변경하지 않는다. */
    @Transactional
    public ProductModel create(Long brandId, String name, Long price) {
        brandRepository.findActive(brandId)
            .orElseThrow(() -> new CoreException(ErrorType.BRAND_NOT_FOUND));

        return productRepository.save(ProductModel.create(brandId, name, price));
    }

    /** 브랜드 관계는 바꾸지 않는다. */
    @Transactional
    public ProductModel update(Long productId, String name, Long price) {
        ProductModel product = findActive(productId);
        product.update(name, price);
        return productRepository.save(product);
    }

    @Transactional
    public void delete(Long productId) {
        ProductModel product = findActive(productId);
        product.delete();
        productRepository.save(product);
    }

    /** quantity 는 증감량이 아니라 변경 후의 최종 수량이다. */
    @Transactional
    public ProductModel changeStock(Long productId, Long finalQuantity) {
        ProductModel product = findActive(productId);

        StockChange change = product.changeStock(finalQuantity);

        stockHistoryRepository.save(StockHistoryModel.changedByAdmin(product.getId(), change));
        return productRepository.save(product);
    }

    private ProductModel findActive(Long productId) {
        return productRepository.findActive(productId)
            .orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND));
    }
}
