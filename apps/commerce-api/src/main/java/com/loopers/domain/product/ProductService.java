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
     * FR-ORDER-02: getActiveProducts 와 같은 검증을 하되, 재고 행(product_id 오름차순, 쓰기 락) → 상품 행(id 오름차순, 읽기 락) 순으로 잠그며 읽는다 (DR-34, DR-38).
     * 재고 행 락이 확정끼리를 줄 세우고, 상품 행 읽기 락이 확정 중 상품 수정·삭제를 막는다. 확정끼리는 상품 행에서 서로 기다리지 않는다.
     * 확정 트랜잭션에서 재고·상품을 처음 읽는 조회여야 한다. 락 없이 먼저 읽힌 엔티티는 1차 캐시의 옛 값이 그대로 쓰인다.
     */
    public List<ProductModel> getActiveProductsForReserve(Collection<Long> productIds) {
        productRepository.findStocksForUpdate(productIds.stream().filter(Objects::nonNull).distinct().toList());
        return getActiveProducts(productIds, productRepository::findByIdsForShare);
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

    /**
     * 브랜드 존재·ACTIVE 검증(INV-10)은 Facade 가 BrandService 로 같은 트랜잭션에서 한다 (DR-04).
     * 현재 재고 행을 0 으로 만들고 초기 재고까지 ADJUSTMENT 로 맞춘다 (DR-38). 초기 재고가 0 이면 원장 행이 없다.
     */
    public ProductModel create(Long brandId, String name, Long price, Integer stock) {
        ProductModel product = productRepository.save(new ProductModel(brandId, name, price, stock));
        ProductStockModel productStock = productRepository.saveStock(new ProductStockModel(product.getId()));
        productStock.adjustTo(stock).ifPresent(productRepository::saveLedger);
        return product;
    }

    public ProductModel update(Long productId, String name, Long price) {
        ProductModel product = getActive(productId);
        product.update(name, price);
        return product;
    }

    /** ST-02 ACTIVE → DELETED. 좋아요 관계·주문 품목은 건드리지 않는다 (ASM-07, 원문). 재고 행·원장은 그대로 둔다 (DR-38). */
    public void delete(Long productId) {
        ProductModel product = getActive(productId);
        product.delete();
    }

    /** 현재 재고. 상품마다 행이 있어야 한다 (DR-38). 없으면 IllegalStateException. */
    private ProductStockModel getStock(Long productId) {
        return productRepository.findStock(productId)
            .orElseThrow(() -> new IllegalStateException("[productId = " + productId + "] 재고 행이 없습니다."));
    }

    /** 현재 재고 수량. 행이 없는 상품 ID 는 결과에 없다. */
    public Map<Long, Integer> stocksOf(Collection<Long> productIds) {
        return productRepository.findStocks(productIds).stream()
            .collect(Collectors.toMap(ProductStockModel::getProductId, ProductStockModel::getAvailable));
    }

    /** FR-ADMIN-PRODUCT-06: 최종 수량 설정. 재고 행을 쓰기 락으로 읽어 맞추고, 차이만큼 ADJUSTMENT 를 남긴다 (DR-38). */
    public ProductStockModel updateStock(Long productId, Integer stock) {
        getActive(productId);
        ProductStockModel productStock = productRepository.findStocksForUpdate(List.of(productId)).stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("[productId = " + productId + "] 재고 행이 없습니다."));
        productStock.adjustTo(stock).ifPresent(productRepository::saveLedger);
        productRepository.syncLegacyStock(productId, productStock.getAvailable());
        return productStock;
    }

    /** FR-ORDER-02: 품목마다 재고 예약. 재고·상품은 이미 getActiveProductsForReserve 로 검증·잠금된 것이어야 한다. RESERVE 를 남긴다 (DR-38). */
    public void reserveStock(Long orderId, Long productId, int quantity) {
        ProductStockModel productStock = getStock(productId);
        productRepository.saveLedger(productStock.reserve(orderId, quantity));
        productRepository.syncLegacyStock(productId, productStock.getAvailable());
    }

    /** 결제 실패: 품목별 예약분 반환. 재고 행을 product_id 오름차순 쓰기 락으로 읽고 RELEASE 를 남긴다 (DR-34, DR-38). 삭제된 상품도 반환한다. */
    public void releaseStocks(Long orderId, Map<Long, Integer> quantitiesByProductId) {
        for (ProductStockModel productStock : productRepository.findStocksForUpdate(List.copyOf(quantitiesByProductId.keySet()))) {
            productRepository.saveLedger(productStock.release(orderId, quantitiesByProductId.get(productStock.getProductId())));
            productRepository.syncLegacyStock(productStock.getProductId(), productStock.getAvailable());
        }
    }

    /**
     * INV-10 (FR-ADMIN-BRAND-05): 삭제되지 않은 소속 상품(재고 0 포함)을 전부 ST-02 ACTIVE → DELETED.
     * 이미 삭제된 상품은 건드리지 않는다. 좋아요 관계·주문 품목도 건드리지 않는다 (ASM-07, DR-33).
     */
    public void deleteAllOfBrand(Long brandId) {
        productRepository.findActiveByBrandId(brandId).forEach(ProductModel::delete);
    }

    /**
     * 고객 상품 목록 캐시의 revision 을 올린다 (DR-37). 목록 결과(상품·브랜드 정보·totalCount)를 바꿀 수 있는 쓰기마다,
     * 그 트랜잭션의 마지막 호출로 부른다. 첫 페이지에 실제로 영향이 있는지는 따지지 않는다.
     */
    public void bumpListRevision() {
        productRepository.increaseListRevision();
    }
}
