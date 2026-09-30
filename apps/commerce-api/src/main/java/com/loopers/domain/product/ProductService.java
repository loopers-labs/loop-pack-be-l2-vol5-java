package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public ProductModel getProduct(Long id) {
        return productRepository.find(id)
            .filter(product -> product.getDeletedAt() == null)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 상품을 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public List<ProductModel> getProducts() {
        return productRepository.findAllActive();
    }

    @Transactional(readOnly = true)
    public Page<ProductModel> getProducts(Long brandId, ProductSort sort, Pageable pageable) {
        return productRepository.findActive(brandId, sort, pageable);
    }

    /**
     * 목록 조회처럼 N개를 한 번에 처리할 때 N+1을 피하기 위한 일괄 조회 (BrandService.getBrandsByIds와 동일 원칙).
     * getProduct(단건)와 동일하게 삭제된 상품은 걸러낸다 — 호출자는 요청한 id 집합과 결과를 비교해
     * 빠진 id를 "없거나 삭제됨"으로 일관되게 판단할 수 있다.
     */
    @Transactional(readOnly = true)
    public List<ProductModel> getProductsByIds(List<Long> ids) {
        return productRepository.findAllByIds(ids).stream()
            .filter(product -> product.getDeletedAt() == null)
            .toList();
    }

    /**
     * 주어진 브랜드를 참조하는 삭제되지 않은 상품이 하나라도 있는지 확인한다.
     * 재고 수량과 무관하다 — 재고 0인 상품도 "삭제되지 않았다"는 사실만으로 포함된다.
     */
    @Transactional(readOnly = true)
    public boolean hasActiveProduct(Long brandId) {
        return productRepository.existsActiveByBrandId(brandId);
    }

    @Transactional
    public ProductModel createProduct(String name, Long price, Long brandId, int initialStock) {
        ProductModel product = new ProductModel(name, price, brandId, initialStock);
        return productRepository.save(product);
    }

    @Transactional
    public ProductModel updateProduct(Long id, String name, Long price) {
        ProductModel product = getProduct(id);
        product.update(name, price);
        return productRepository.save(product);
    }

    @Transactional
    public ProductModel changeStock(Long id, int quantity) {
        ProductModel product = getProduct(id);
        product.changeStock(quantity);
        return productRepository.save(product);
    }

    /**
     * 주문 확정 시 재고를 상대적으로 차감한다. 비관적 락으로 조회해 동시 확정 간 경합을 막는다
     * (docs/week2/design.md 5번 섹션). 확정 시점에도 삭제 여부를 다시 확인한다 — 생성 시점엔
     * 있던 상품이 확정 전에 삭제됐을 수 있다(3번 섹션 "확정 시에도 상품 삭제 여부를 다시 확인한다").
     */
    @Transactional
    public ProductModel decreaseStock(Long id, int quantity) {
        // findForUpdate로 이미 영속 상태로 조회했으므로 dirty checking이 커밋 시점에 자동 반영한다.
        // save()를 다시 호출하면(merge) 락을 다시 획득하는 select가 한 번 더 나가는 걸 관찰해서 뺐다.
        ProductModel product = productRepository.findForUpdate(id)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 상품을 찾을 수 없습니다."));
        product.decreaseStock(quantity);
        return product;
    }

    @Transactional
    public void deleteProduct(Long id) {
        ProductModel product = getProduct(id);
        product.delete();
        productRepository.save(product);
    }
}
