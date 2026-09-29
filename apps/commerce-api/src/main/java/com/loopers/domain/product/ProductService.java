package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@RequiredArgsConstructor
@Component
public class ProductService {

    private final ProductRepository productRepository;

    /**
     * 증감이 아니라 최종 수량으로 재고를 설정한다.
     */
    @Transactional
    public Product changeStock(Long productId, int quantity) {
        Product product = findActiveProduct(productId);
        product.changeStock(quantity);
        return product;
    }

    @Transactional
    public Product deductStock(Long productId, int quantity) {
        Product product = findActiveProduct(productId);
        product.deductStock(quantity);
        return product;
    }

    @Transactional
    public Product createProduct(Long brandId, String name, Price price) {
        return productRepository.save(new Product(brandId, name, price));
    }

    /**
     * 이름과 가격만 수정한다. 브랜드는 고정이고 재고는 changeStock 으로 변경한다.
     */
    @Transactional
    public Product updateProduct(Long productId, String name, Price price) {
        Product product = findActiveProduct(productId);
        product.update(name, price);
        return product;
    }

    /**
     * 논리 삭제한다. 주문·좋아요가 참조하는 레코드는 그대로 남는다.
     */
    @Transactional
    public void deleteProduct(Long productId) {
        findActiveProduct(productId).delete();
    }

    /**
     * 존재하며 삭제되지 않은 상품을 반환한다.
     */
    @Transactional(readOnly = true)
    public Product getActiveProduct(Long productId) {
        return findActiveProduct(productId);
    }

    /**
     * 삭제되지 않은 상품 목록을 조회한다.
     *
     * @param sort 지원하지 않는 값이면 BAD_REQUEST 로 거절한다.
     */
    @Transactional(readOnly = true)
    public List<Product> getProducts(Long brandId, String sort, int page, int size) {
        ProductSortType sortType = ProductSortType.from(sort);
        return productRepository.findAll(brandId, sortType, page, size);
    }

    /**
     * 관리자 목록 조회. 삭제된 상품도 포함한다.
     */
    @Transactional(readOnly = true)
    public List<Product> getProductsForAdmin(Long brandId, int page, int size) {
        return productRepository.findAllForAdmin(brandId, page, size);
    }

    /**
     * 주어진 식별자 중 존재하며 삭제되지 않은 상품만 반환한다.
     * 없거나 삭제된 상품은 조용히 제외한다.
     */
    @Transactional(readOnly = true)
    public List<Product> findActiveProducts(Collection<Long> productIds) {
        return productRepository.findAllByIdIn(productIds).stream()
            .filter(product -> product.getDeletedAt() == null)
            .toList();
    }

    private Product findActiveProduct(Long productId) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> notFound(productId));

        if (product.getDeletedAt() != null) {
            throw notFound(productId);
        }
        return product;
    }

    private CoreException notFound(Long productId) {
        return new CoreException(ErrorType.NOT_FOUND, "[id = " + productId + "] 상품을 찾을 수 없습니다.");
    }
}
