package com.loopers.application.product;

import com.loopers.application.brand.BrandInfo;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.CachedFirstPage;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.PopularPage;
import com.loopers.domain.product.PopularPageCache;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ProductQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Comparator<CustomerProductInfo> LATEST_FIRST =
        Comparator.comparing(CustomerProductInfo::createdAt).reversed()
            .thenComparing(CustomerProductInfo::id, Comparator.reverseOrder());

    private final BrandService brandService;
    private final ProductService productService;
    private final LikeService likeService;
    private final PricePageReader pricePages;
    private final PopularPageCache popularPages;
    private final boolean popularCacheEnabled;

    public ProductQueryService(BrandService brandService, ProductService productService, LikeService likeService,
        PricePageReader pricePages, PopularPageCache popularPages,
        @Value("${commerce.cache.popular.enabled:false}") boolean popularCacheEnabled) {
        this.brandService = brandService;
        this.productService = productService;
        this.likeService = likeService;
        this.pricePages = pricePages;
        this.popularPages = popularPages;
        this.popularCacheEnabled = popularCacheEnabled;
    }

    @Transactional(readOnly = true)
    public CustomerProductInfo get(Long productId) {
        ProductModel product = productService.get(productId);
        return enrich(List.of(product)).getFirst();
    }

    @Transactional(readOnly = true)
    public ProductPage<CustomerProductInfo> getList(
        Long brandId,
        int page,
        int size,
        ProductSort sort
    ) {
        validatePage(page, size);
        if (sort == ProductSort.POPULAR && (brandId == null || !CachedFirstPage.matches(page, size))) {
            throw new CoreException(ErrorType.BAD_REQUEST, "인기순은 브랜드별 첫 페이지 20개만 조회할 수 있습니다.");
        }
        BrandModel brand = brandId == null ? null : brandService.get(brandId);
        if (sort == ProductSort.PRICE_ASC) {
            if (brand != null) {
                var stored = pricePages.get(brandId, page, size);
                var counts = likeService.countByProductIds(stored.items().stream().map(i -> i.id()).toList());
                var items = stored.items().stream().map(i -> new CustomerProductInfo(i.id(), BrandInfo.from(brand),
                    i.name(), i.price(), counts.getOrDefault(i.id(), 0L), i.createdAt())).toList();
                return new ProductPage<>(items, page, size);
            }
            List<ProductModel> candidates = productService.findActiveByPriceAsc(brandId, page, size);
            return new ProductPage<>(enrich(candidates, brand), page, size);
        }
        if (sort == ProductSort.LIKES_DESC || sort == ProductSort.POPULAR) {
            return byLikes(brandId, brand, page, size, sort);
        }
        // 최신순만 남은 앱 정렬 경로다.
        List<ProductModel> candidates = brandId == null
            ? productService.findAllActive()
            : productService.findActiveByBrandId(brandId);
        List<CustomerProductInfo> products = enrich(candidates, brand).stream().sorted(LATEST_FIRST).toList();
        int fromIndex = Math.min(page * size, products.size());
        int toIndex = Math.min(fromIndex + size, products.size());
        return new ProductPage<>(products.subList(fromIndex, toIndex), page, size);
    }

    /**
     * 좋아요순·인기순은 DB가 정렬해 한 페이지의 ID만 돌려준다. 브랜드 상품 전체를 엔티티로 올려
     * 앱에서 정렬하지 않으므로 앱 메모리는 페이지 크기만큼만 쓴다. 같은 읽기 트랜잭션의 스냅샷에서
     * ID와 상품을 읽어 순서와 표시 값이 어긋나지 않는다.
     */
    private ProductPage<CustomerProductInfo> byLikes(Long brandId, BrandModel brand, int page, int size,
        ProductSort sort) {
        boolean cacheRanking = popularCacheEnabled && sort == ProductSort.POPULAR
            && TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        if (cacheRanking) {
            var cached = popularPages.get(brandId);
            if (cached.isPresent()) {
                var ordered = activeInOrder(cached.get().ids(), brandId);
                if (ordered.size() == cached.get().ids().size()) {
                    return new ProductPage<>(enrich(ordered, brand), page, size);
                }
            }
        }
        var ids = productService.findVisibleIdsByLikesDesc(brandId, page, size);
        var selected = enrich(activeInOrder(ids, brandId), brand);
        if (cacheRanking) { popularPages.put(brandId, new PopularPage(selected.stream().map(CustomerProductInfo::id).toList())); }
        return new ProductPage<>(selected, page, size);
    }

    private List<ProductModel> activeInOrder(List<Long> ids, Long brandId) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, ProductModel> byId = productService.findActiveByIds(ids).stream()
            .filter(product -> brandId == null || brandId.equals(product.getBrandId()))
            .collect(Collectors.toMap(ProductModel::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    @Transactional(readOnly = true)
    public List<CustomerProductInfo> getActiveByIdsInOrder(List<Long> productIds) {
        Map<Long, CustomerProductInfo> byId = enrich(productService.findActiveByIds(productIds)).stream()
            .collect(Collectors.toMap(CustomerProductInfo::id, Function.identity()));
        return productIds.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private List<CustomerProductInfo> enrich(Collection<ProductModel> products) {
        return enrich(products, null);
    }

    /**
     * knownBrand가 있으면 호출자가 이미 조회·검증한 브랜드이므로 다시 조회하지 않는다.
     * 후보 상품은 모두 knownBrand 소속이어야 한다(브랜드 조건으로 조회한 candidates만 넘긴다).
     */
    private List<CustomerProductInfo> enrich(Collection<ProductModel> products, BrandModel knownBrand) {
        List<Long> productIds = products.stream().map(ProductModel::getId).toList();
        Map<Long, BrandInfo> brands = knownBrand != null
            ? Map.of(knownBrand.getId(), BrandInfo.from(knownBrand))
            : brandService.getAllActiveByIds(
                    products.stream().map(ProductModel::getBrandId).collect(Collectors.toSet())
                ).stream()
                .map(BrandInfo::from)
                .collect(Collectors.toMap(BrandInfo::id, Function.identity()));
        Map<Long, Long> likeCounts = likeService.countByProductIds(productIds);
        return products.stream()
            .map(product -> new CustomerProductInfo(
                product.getId(),
                brands.get(product.getBrandId()),
                product.getName(),
                product.getPrice(),
                likeCounts.getOrDefault(product.getId(), 0L),
                product.getCreatedAt()
            ))
            .filter(product -> product.brand() != null)
            .toList();
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new CoreException(ErrorType.BAD_REQUEST, "페이지 번호 또는 크기가 올바르지 않습니다.");
        }
    }
}
