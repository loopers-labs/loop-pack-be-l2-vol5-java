package com.loopers.application.product;

import com.loopers.domain.brand.BrandService;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// 상품 조회 전담 — 고객·관리자 둘 다 여기서 가져간다. 조회는 같은 이유로 바뀌므로 공유한다.
// (docs/week2/design.md 1번 섹션: 조회 vs 변경 원칙, ProductAdminFacade와 대칭)
// 고객 조회(상세·목록)는 cache-aside다: 캐시에 없는 것만 DB에서 읽어 채운다 (docs/week4/design.md 6번 섹션).
// 관리자 조회(getProduct·getProducts)는 방금 바꾼 값을 바로 봐야 해서 캐시하지 않는다.
@Slf4j
@RequiredArgsConstructor
@Component
public class ProductFacade {

    private final ProductService productService;
    private final BrandService brandService;
    private final LikeService likeService;
    private final ProductCache productCache;

    public ProductInfo getProduct(Long id) {
        return ProductInfo.from(productService.getProduct(id));
    }

    public List<ProductInfo> getProducts() {
        return productService.getProducts().stream()
            .map(ProductInfo::from)
            .toList();
    }

    /**
     * 고객 상품 상세 — 상품 키(product:{id})와 브랜드 키(brand:{id})를 각각 cache-aside로 읽어 조합한다.
     * 상품이 없거나 삭제됐으면 productService가 NOT_FOUND를 던지고, 캐시에는 아무것도 넣지 않는다.
     */
    public ProductDetailInfo getProductDetail(Long id) {
        CacheSession cache = new CacheSession();

        CachedProduct product = cache.getProducts(List.of(id)).get(id);
        if (product == null) {
            ProductModel model = productService.getProduct(id);
            product = CachedProduct.of(model, likeService.getLikeCount(id));
            cache.putProducts(List.of(product));
        }

        Long brandId = product.brandId();
        CachedBrand brand = cache.getBrands(List.of(brandId)).get(brandId);
        if (brand == null) {
            brand = CachedBrand.from(brandService.getBrand(brandId));
            cache.putBrands(List.of(brand));
        }

        return ProductDetailInfo.from(product, brand);
    }

    /**
     * 고객 상품 목록 — "어떤 상품이 어떤 순서로"(목록 키)와 "각 상품의 내용"(상품·브랜드 키)을 나눠 읽는다
     * (docs/week4/design.md 6번 섹션 "키와 값").
     * 목록 키를 놓치면 기존처럼 정렬·페이지네이션을 DB(ProductService)에 맡기고, 그때 읽은 상품으로 상품 키까지 채운다.
     * 목록 키가 맞으면 상품 키를 MGET하고 빠진 상품만 일괄조회한다. 일괄조회는 N+1을 피하기 위한 것이다
     * (docs/week2/design.md 3번 섹션).
     */
    public Page<ProductDetailInfo> getProductList(Long brandId, ProductSort sort, Pageable pageable) {
        CacheSession cache = new CacheSession();
        ProductListLookup lookup = cache.lookupProductIds(brandId, sort, pageable.getPageNumber(), pageable.getPageSize());

        List<Long> ids;
        long totalElements;
        Map<Long, CachedProduct> productsById;
        if (lookup.result() instanceof CacheResult.Hit<CachedProductIds>(CachedProductIds cached)) {
            ids = cached.ids();
            totalElements = cached.totalElements();
            productsById = loadProducts(cache, ids);
        } else {
            Page<ProductModel> page = productService.getProducts(brandId, sort, pageable);
            ids = page.getContent().stream().map(ProductModel::getId).toList();
            totalElements = page.getTotalElements();
            productsById = toCachedProducts(page.getContent(), ids);
            // lookup 때 읽은 버전의 키에 그대로 넣는다 — 버전을 다시 읽으면 그 사이의 무효화가 무력해진다 (ProductListLookup 참고)
            cache.putProductIds(lookup, new CachedProductIds(ids, totalElements));
            cache.putProducts(productsById.values());
        }

        List<Long> brandIds = productsById.values().stream().map(CachedProduct::brandId).distinct().toList();
        Map<Long, CachedBrand> brandsById = loadBrands(cache, brandIds);

        // ids 순서대로 조립한다. DB에서도 찾지 못한 상품(무효화 실패 창에서만 생기는, 이미 삭제된 상품)은 건너뛴다.
        List<ProductDetailInfo> infos = ids.stream()
            .map(productsById::get)
            .filter(Objects::nonNull)
            .filter(product -> hasBrand(product, brandsById))
            .map(product -> ProductDetailInfo.from(product, brandsById.get(product.brandId())))
            .toList();

        return new PageImpl<>(infos, pageable, totalElements);
    }

    // 브랜드를 찾지 못한 상품은 응답에서 빠진다. 미삭제 상품의 브랜드가 없는 것은 데이터 이상 신호라 조용히 빠지지 않게 WARN으로 남긴다.
    private static boolean hasBrand(CachedProduct product, Map<Long, CachedBrand> brandsById) {
        if (brandsById.containsKey(product.brandId())) {
            return true;
        }
        log.warn("상품 목록에서 브랜드를 찾지 못해 상품을 건너뜀 [productId = {}, brandId = {}]", product.id(), product.brandId());
        return false;
    }

    // 상품 키를 MGET하고, 빠진 상품만 DB에서 일괄조회해 채운다.
    private Map<Long, CachedProduct> loadProducts(CacheSession cache, List<Long> ids) {
        Map<Long, CachedProduct> found = new HashMap<>(cache.getProducts(ids));
        List<Long> missing = ids.stream().filter(id -> !found.containsKey(id)).distinct().toList();
        if (missing.isEmpty()) {
            return found;
        }
        Map<Long, CachedProduct> loaded = toCachedProducts(productService.getProductsByIds(missing), missing);
        found.putAll(loaded);
        cache.putProducts(loaded.values());
        return found;
    }

    // 상품 엔티티에 좋아요 수를 일괄조회로 붙여 캐시 값으로 바꾼다. 순서는 products 순서를 유지한다.
    private Map<Long, CachedProduct> toCachedProducts(List<ProductModel> products, List<Long> ids) {
        Map<Long, CachedProduct> result = new LinkedHashMap<>();
        if (products.isEmpty()) {
            return result;
        }
        Map<Long, Long> likeCounts = likeService.getLikeCounts(ids);
        products.forEach(product ->
            result.put(product.getId(), CachedProduct.of(product, likeCounts.getOrDefault(product.getId(), 0L))));
        return result;
    }

    // 브랜드 키를 MGET하고, 빠진 브랜드만 DB에서 일괄조회해 채운다.
    private Map<Long, CachedBrand> loadBrands(CacheSession cache, List<Long> brandIds) {
        Map<Long, CachedBrand> found = new HashMap<>(cache.getBrands(brandIds));
        List<Long> missing = brandIds.stream().filter(id -> !found.containsKey(id)).toList();
        if (missing.isEmpty()) {
            return found;
        }
        List<CachedBrand> loaded = brandService.getBrandsByIds(missing).stream().map(CachedBrand::from).toList();
        loaded.forEach(brand -> found.put(brand.id(), brand));
        cache.putBrands(loaded);
        return found;
    }

    /**
     * 한 요청 동안의 캐시 사용 상태. Redis 호출이 한 번이라도 실패하면(unavailable 또는 저장 false)
     * 그 요청의 남은 Redis 호출을 모두 건너뛰고 DB로만 응답한다 — 재시도하지 않는다 (docs/week4/design.md 8번 섹션).
     * 명령마다 타임아웃이 걸리므로, 건너뛰지 않으면 Redis가 멈춘 동안 요청 하나가 명령 수만큼 늦어진다.
     */
    private final class CacheSession {

        private boolean available = true;

        ProductListLookup lookupProductIds(Long brandId, ProductSort sort, int page, int size) {
            ProductListLookup lookup = productCache.lookupProductIds(brandId, sort, page, size);
            if (lookup.result().isUnavailable()) {
                available = false;
            }
            return lookup;
        }

        void putProductIds(ProductListLookup lookup, CachedProductIds value) {
            if (available && !productCache.putProductIds(lookup, value)) {
                available = false;
            }
        }

        Map<Long, CachedProduct> getProducts(Collection<Long> ids) {
            if (!available || ids.isEmpty()) {
                return Map.of();
            }
            return valueOrEmpty(productCache.getProducts(ids));
        }

        void putProducts(Collection<CachedProduct> products) {
            if (available && !products.isEmpty() && !productCache.putProducts(products)) {
                available = false;
            }
        }

        Map<Long, CachedBrand> getBrands(Collection<Long> ids) {
            if (!available || ids.isEmpty()) {
                return Map.of();
            }
            return valueOrEmpty(productCache.getBrands(ids));
        }

        void putBrands(Collection<CachedBrand> brands) {
            if (available && !brands.isEmpty() && !productCache.putBrands(brands)) {
                available = false;
            }
        }

        private <T> Map<Long, T> valueOrEmpty(CacheResult<Map<Long, T>> result) {
            if (result instanceof CacheResult.Hit<Map<Long, T>>(Map<Long, T> value)) {
                return value;
            }
            if (result.isUnavailable()) {
                available = false;
            }
            return Map.of();
        }
    }
}
