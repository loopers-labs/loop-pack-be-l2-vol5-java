package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class ProductService {

    private final ProductRepository productRepository;

    /** 존재하고 삭제되지 않은 상품. 없음·삭제됨 모두 ER-04 PRODUCT_NOT_FOUND. */
    public ProductModel getActive(Long productId) {
        ProductModel product = get(productId);
        if (product.isDeleted()) {
            throw new CoreException(ErrorType.PRODUCT_NOT_FOUND, "[id = " + productId + "] 삭제된 상품입니다.");
        }
        return product;
    }

    /** 존재하는 상품. 삭제 여부 무관 (ASM-15 관리자 상세, FR-LIKE-02 취소). */
    public ProductModel get(Long productId) {
        return productRepository.find(productId)
            .orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND, "[id = " + productId + "] 상품을 찾을 수 없습니다."));
    }

    /**
     * FR-ORDER-01/02: 품목의 상품 전부가 존재하고 삭제되지 않아야 한다. 하나라도 아니면 ER-04.
     * 반환 순서는 입력 순서(중복 제거).
     */
    public List<ProductModel> getActiveProducts(Collection<Long> productIds) {
        return getActiveProducts(productIds, productRepository::findByIds);
    }

    /**
     * FR-ORDER-02: getActiveProducts 와 같은 검증을 하되, 상품 행을 비관적 쓰기 락으로 잠그며 읽는다 (DR-34).
     * 확정 트랜잭션에서 상품을 처음 읽는 조회여야 한다. 락 없이 먼저 읽힌 엔티티는 1차 캐시의 옛 값이 그대로 쓰인다.
     */
    public List<ProductModel> getActiveProductsForUpdate(Collection<Long> productIds) {
        return getActiveProducts(productIds, productRepository::findByIdsForUpdate);
    }

    private List<ProductModel> getActiveProducts(Collection<Long> productIds, Function<List<Long>, List<ProductModel>> loader) {
        if (productIds.stream().anyMatch(Objects::isNull)) {
            throw new CoreException(ErrorType.PRODUCT_NOT_FOUND, "상품이 지정되지 않은 품목이 있습니다.");
        }
        List<Long> distinctIds = List.copyOf(new LinkedHashSet<>(productIds));
        Map<Long, ProductModel> found = loader.apply(distinctIds).stream()
            .collect(Collectors.toMap(ProductModel::getId, Function.identity()));
        return distinctIds.stream().map(id -> {
            ProductModel product = found.get(id);
            if (product == null || product.isDeleted()) {
                throw new CoreException(ErrorType.PRODUCT_NOT_FOUND, "[id = " + id + "] 상품을 찾을 수 없습니다.");
            }
            return product;
        }).toList();
    }

    /** 브랜드 존재·ACTIVE 검증(INV-10)은 Facade 가 BrandService 로 같은 트랜잭션에서 한다 (DR-04). */
    public ProductModel create(Long brandId, String name, Long price, Integer stock) {
        return productRepository.save(new ProductModel(brandId, name, price, stock));
    }

    public ProductModel update(Long productId, String name, Long price) {
        ProductModel product = getActive(productId);
        product.update(name, price);
        return product;
    }

    /** ST-02 ACTIVE → DELETED. 좋아요 관계·주문 품목은 건드리지 않는다 (ASM-07, 원문). */
    public void delete(Long productId) {
        ProductModel product = getActive(productId);
        product.delete();
    }

    public ProductModel updateStock(Long productId, Integer stock) {
        ProductModel product = getActive(productId);
        product.changeStock(stock);
        return product;
    }

    /** FR-ORDER-02: 품목마다 재고 차감. 상품은 이미 getActiveProductsForUpdate 로 검증·잠금된 것이어야 한다. */
    public void deductStock(Long productId, int quantity) {
        ProductModel product = getActive(productId);
        product.deductStock(quantity);
    }

    /**
     * INV-10 (FR-ADMIN-BRAND-05): 삭제되지 않은 소속 상품(재고 0 포함)을 전부 ST-02 ACTIVE → DELETED.
     * 이미 삭제된 상품은 건드리지 않는다. 좋아요 관계·주문 품목도 건드리지 않는다 (ASM-07, DR-33).
     */
    public void deleteAllOfBrand(Long brandId) {
        productRepository.findActiveByBrandId(brandId).forEach(ProductModel::delete);
    }
}
