package com.loopers.domain.product;

import com.loopers.domain.common.PageCondition;
import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public Product getProduct(Long id) {
        return productRepository.findActive(id)
            .orElseThrow(() -> new DomainException(DomainErrorType.NOT_FOUND, "[productId = " + id + "] 상품을 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public void verifyActive(Long id) {
        getProduct(id);
    }

    @Transactional(readOnly = true)
    public List<ProductWithLikeCount> searchProducts(ProductSearchCondition condition) {
        return productRepository.search(condition);
    }

    @Transactional(readOnly = true)
    public long countProducts(ProductSearchCondition condition) {
        return productRepository.count(condition);
    }

    @Transactional(readOnly = true)
    public List<Product> getLikedProducts(Long userId, PageCondition page) {
        return productRepository.findActiveLikedBy(userId, page);
    }

    @Transactional(readOnly = true)
    public long countLikedProducts(Long userId) {
        return productRepository.countActiveLikedBy(userId);
    }

    // 주문 생성·확정용: 삭제되지 않은 상품만 조회한다
    @Transactional(readOnly = true)
    public List<Product> getActiveProducts(Collection<Long> ids) {
        return productRepository.findAllActive(ids);
    }

    // 주문 내역 조회용: 삭제된 상품의 이름도 포함한다(DEL-003, T-5)
    @Transactional(readOnly = true)
    public Map<Long, String> getProductNamesIncludingDeleted(Collection<Long> ids) {
        return productRepository.findAll(ids).stream()
            .collect(Collectors.toMap(Product::getId, Product::getName));
    }

    // 브랜드 존재·삭제 여부 확인은 application이 맡는다(PRD-001)
    @Transactional
    public Product register(Long brandId, String name, Long price, Long stock) {
        return productRepository.save(new Product(brandId, name, price, stock));
    }

    @Transactional
    public Product update(Long id, String name, Long price) {
        Product product = getProduct(id);
        product.update(name, price);
        return product;
    }

    @Transactional
    public Product changeStock(Long id, Long stock) {
        Product product = getProduct(id);
        product.changeStock(stock);
        return product;
    }

    @Transactional
    public void delete(Long id) {
        getProduct(id).delete();
    }

    // 재고 0인 상품도 삭제되지 않았으면 포함한다(DEL-001)
    @Transactional(readOnly = true)
    public boolean hasActiveProducts(Long brandId) {
        return productRepository.existsActiveByBrandId(brandId);
    }

    // 관리자 목록: 좋아요 수 없이 삭제 제외·최신순으로 조회한다. brandId가 null이면 전체
    @Transactional(readOnly = true)
    public List<Product> getLatestProducts(Long brandId, PageCondition page) {
        return productRepository.findActiveLatest(brandId, page);
    }

    @Transactional(readOnly = true)
    public long countActiveProducts(Long brandId) {
        return productRepository.countActive(brandId);
    }
}
