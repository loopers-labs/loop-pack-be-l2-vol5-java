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
     * brandId로 고른 미삭제 상품을 전부 삭제한다 — 재고 0인 상품도 포함.
     * "브랜드가 삭제되면 상품도 내린다"는 이유는 호출자(BrandAdminFacade)만 안다.
     * 엔티티 delete()를 거쳐 멱등 규칙·@PreUpdate가 그대로 적용되게 하고, bulk UPDATE는 쓰지 않는다
     * (docs/week3/design.md 3번 섹션). 매니지드 엔티티라 save() 없이 dirty checking으로 반영된다.
     */
    @Transactional
    public void deleteAllByBrandId(Long brandId) {
        productRepository.findAllActiveByBrandIdForUpdate(brandId).forEach(ProductModel::delete);
    }

    @Transactional
    public ProductModel createProduct(String name, Long price, Long brandId, int initialStock) {
        ProductModel product = new ProductModel(name, price, brandId, initialStock);
        return productRepository.save(product);
    }

    @Transactional
    public ProductModel updateProduct(Long id, String name, Long price) {
        ProductModel product = getActiveProductForUpdate(id);
        product.update(name, price);
        return product;
    }

    @Transactional
    public ProductModel changeStock(Long id, int quantity) {
        ProductModel product = getActiveProductForUpdate(id);
        product.changeStock(quantity);
        return product;
    }

    /**
     * 주문 확정 시 재고를 상대적으로 차감한다. 확정 시점에도 삭제 여부를 다시 확인한다 — 생성 시점엔
     * 있던 상품이 확정 전에 삭제됐을 수 있다(docs/week2/design.md 3번 섹션).
     */
    @Transactional
    public ProductModel decreaseStock(Long id, int quantity) {
        ProductModel product = getActiveProductForUpdate(id);
        product.decreaseStock(quantity);
        return product;
    }

    @Transactional
    public void deleteProduct(Long id) {
        getActiveProductForUpdate(id).delete();
    }

    /**
     * 상품 행을 바꾸는 모든 경로(차감·재고 설정·수정·삭제)가 같은 비관적 락에 참여한다.
     * 한 경로라도 일반 SELECT로 읽으면, Hibernate가 전체 컬럼을 다시 쓰면서 그 사이 commit된
     * 주문 차감을 옛 재고로 덮어쓴다 (docs/week3/design.md 6번 섹션).
     * 이미 영속 상태라 dirty checking으로 반영된다 — save()(merge)를 다시 부르면 락 조회가 한 번 더 나간다.
     */
    private ProductModel getActiveProductForUpdate(Long id) {
        return productRepository.findForUpdate(id)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 상품을 찾을 수 없습니다."));
    }
}
