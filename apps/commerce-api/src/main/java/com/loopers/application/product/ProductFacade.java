package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.CachedProductItem;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductListCache;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSortType;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class ProductFacade {

    private static final int CACHED_PAGE_SIZE = 20;
    private static final int CACHED_LAST_PAGE = 2;
    private static final String CACHE_METRIC = "product.list.cache";

    private final ProductService productService;
    private final BrandRepository brandRepository;
    private final LikeService likeService;
    private final ProductListCache productListCache;
    private final MeterRegistry meterRegistry;

    /**
     * 브랜드 필터 + 가격순 + 20개 + 0~2페이지 요청만 캐시를 거친다. 그 외는 DB 경로 그대로다.
     * hit 는 캐시 목록에 현재 재고를 붙여 응답하고, 캐시 안에 삭제된 상품이 섞여 있으면 DB 경로로 돌아가 다시 저장한다.
     * 캐시 조건에 맞는 요청 1회마다 카운터 hit / miss / hit_check_failed 중 하나만 1 늘린다.
     * 캐시 조회 오류로 빈 값이 돌아온 경우도 miss 로 센다 (miss = 캐시를 못 써서 DB 로 간 횟수).
     */
    public List<ProductInfo> getProducts(Long brandId, String sort, int page, int size) {
        if (!isCacheable(brandId, sort, page, size)) {
            return loadProducts(brandId, sort, page, size);
        }

        Optional<List<CachedProductItem>> cachedItems = productListCache.find(brandId, page);
        if (cachedItems.isPresent()) {
            Optional<List<ProductInfo>> withStock = withCurrentStock(cachedItems.get());
            if (withStock.isPresent()) {
                countCache("hit");
                return withStock.get();
            }
            countCache("hit_check_failed");
        } else {
            countCache("miss");
        }

        List<ProductInfo> infos = loadProducts(brandId, sort, page, size);
        productListCache.save(brandId, page, infos.stream().map(this::toCachedItem).toList());
        return infos;
    }

    private void countCache(String result) {
        meterRegistry.counter(CACHE_METRIC, "result", result).increment();
    }

    private boolean isCacheable(Long brandId, String sort, int page, int size) {
        return brandId != null
            && ProductSortType.PRICE_ASC.getValue().equals(sort)
            && size == CACHED_PAGE_SIZE
            && page >= 0
            && page <= CACHED_LAST_PAGE;
    }

    /**
     * 캐시 목록에 DB 의 현재 재고를 붙인다. 삭제된 상품이 섞여 있으면(재고 조회 건수 불일치) empty 를 돌려 DB 경로로 보낸다.
     */
    private Optional<List<ProductInfo>> withCurrentStock(List<CachedProductItem> items) {
        if (items.isEmpty()) {
            return Optional.of(List.of());
        }

        Map<Long, Integer> stocks = productService.getActiveStockQuantities(
            items.stream().map(CachedProductItem::productId).toList());
        if (stocks.size() != items.size()) {
            return Optional.empty();
        }

        return Optional.of(items.stream()
            .map(item -> new ProductInfo(
                item.productId(),
                item.productName(),
                item.price(),
                stocks.get(item.productId()),
                item.brandId(),
                item.brandName(),
                item.likeCount()))
            .toList());
    }

    private CachedProductItem toCachedItem(ProductInfo info) {
        return new CachedProductItem(
            info.productId(), info.productName(), info.price(), info.brandId(), info.brandName(), info.likeCount());
    }

    private List<ProductInfo> loadProducts(Long brandId, String sort, int page, int size) {
        List<Product> products = productService.getProducts(brandId, sort, page, size);
        if (products.isEmpty()) {
            return List.of();
        }

        Map<Long, Long> likeCounts = likeService.countByProductIds(
            products.stream().map(Product::getId).toList());
        Map<Long, String> brandNames = findBrandNames(products);

        return products.stream()
            .map(product -> ProductInfo.of(
                product,
                brandNames.get(product.getBrandId()),
                likeCounts.getOrDefault(product.getId(), 0L)))
            .toList();
    }

    /**
     * 증감이 아니라 최종 수량으로 재고를 설정한다.
     *
     * @return 변경된 재고 수량
     */
    @Transactional
    public int changeStock(Long productId, int quantity) {
        return productService.changeStock(productId, quantity);
    }

    /**
     * 브랜드가 존재하고 삭제되지 않았는지 확인한 뒤 등록한다.
     */
    @Transactional
    public ProductInfo createProduct(Long brandId, String name, long price) {
        Brand brand = requireActiveBrand(brandId);
        Product product = productService.createProduct(brandId, name, new Price(price));
        productListCache.evictBrandAfterCommit(brandId);

        return ProductInfo.of(product, brand.getName(), 0L);
    }

    @Transactional
    public ProductInfo updateProduct(Long productId, String name, long price) {
        Product product = productService.updateProduct(productId, name, new Price(price));
        productListCache.evictBrandAfterCommit(product.getBrandId());
        String brandName = brandRepository.findById(product.getBrandId())
            .map(Brand::getName)
            .orElse(null);

        return ProductInfo.of(product, brandName, likeService.countByProductId(product.getId()));
    }

    /**
     * 무효화할 브랜드를 알기 위해 삭제 전에 상품을 읽는다. 같은 트랜잭션이라 삭제 시 재조회는 영속성 컨텍스트에서 끝난다.
     */
    @Transactional
    public void deleteProduct(Long productId) {
        Product product = productService.getActiveProduct(productId);
        productService.deleteProduct(productId);
        productListCache.evictBrandAfterCommit(product.getBrandId());
    }

    /**
     * 관리자 목록은 삭제된 상품도 포함한다.
     */
    @Transactional(readOnly = true)
    public List<ProductInfo> getProductsForAdmin(Long brandId, int page, int size) {
        List<Product> products = productService.getProductsForAdmin(brandId, page, size);
        if (products.isEmpty()) {
            return List.of();
        }

        Map<Long, Long> likeCounts = likeService.countByProductIds(
            products.stream().map(Product::getId).toList());
        Map<Long, String> brandNames = findBrandNames(products);

        return products.stream()
            .map(product -> ProductInfo.of(
                product,
                brandNames.get(product.getBrandId()),
                likeCounts.getOrDefault(product.getId(), 0L)))
            .toList();
    }

    private Brand requireActiveBrand(Long brandId) {
        Brand brand = brandRepository.findById(brandId)
            .orElseThrow(() -> new CoreException(
                ErrorType.NOT_FOUND, "[id = " + brandId + "] 브랜드를 찾을 수 없습니다."));

        if (brand.getDeletedAt() != null) {
            throw new CoreException(ErrorType.NOT_FOUND, "[id = " + brandId + "] 브랜드를 찾을 수 없습니다.");
        }
        return brand;
    }

    public ProductInfo getProduct(Long productId) {
        Product product = productService.getActiveProduct(productId);
        String brandName = brandRepository.findById(product.getBrandId())
            .map(Brand::getName)
            .orElse(null);

        return ProductInfo.of(product, brandName, likeService.countByProductId(product.getId()));
    }

    /**
     * 브랜드를 상품마다 조회하지 않도록 한 번에 모아 조회한다.
     */
    private Map<Long, String> findBrandNames(List<Product> products) {
        Set<Long> brandIds = products.stream()
            .map(Product::getBrandId)
            .collect(Collectors.toSet());

        return brandRepository.findAllByIdIn(brandIds).stream()
            .collect(Collectors.toMap(Brand::getId, Brand::getName));
    }
}
