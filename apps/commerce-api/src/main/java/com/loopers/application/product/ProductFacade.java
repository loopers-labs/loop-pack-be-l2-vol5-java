package com.loopers.application.product;

import com.loopers.application.product.cache.CachedProductListInfo;
import com.loopers.application.product.cache.ProductListCache;
import com.loopers.application.product.dto.ProductDetailInfo;
import com.loopers.application.product.dto.ProductListItemInfo;
import com.loopers.application.product.dto.ProductListQuery;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSort;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.paging.PageRequests;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 고객용 상품 조회 유스케이스. 상품에 브랜드 정보와 좋아요 수를 조합한다.
 * 요청자에 따라 결과가 달라지지 않는 카탈로그 조회라 요청자를 식별하지 않으며, 형식 오류는 조회보다 먼저 거절한다.
 */
@Component
public class ProductFacade {

    // likes_desc 는 좋아요마다 순서가 바뀌어 캐시하지 않는다.
    private static final Set<ProductSort> CACHED_SORTS = EnumSet.of(ProductSort.LATEST, ProductSort.PRICE_ASC);

    private final ProductService productService;
    private final BrandService brandService;
    private final LikeService likeService;
    private final ProductListCache productListCache;
    private final TransactionTemplate readOnlyTransaction;

    public ProductFacade(
        ProductService productService,
        BrandService brandService,
        LikeService likeService,
        ProductListCache productListCache,
        PlatformTransactionManager transactionManager
    ) {
        this.productService = productService;
        this.brandService = brandService;
        this.likeService = likeService;
        this.productListCache = productListCache;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    @Transactional(readOnly = true)
    public ProductDetailInfo get(Long productId) {
        Product product = productService.get(productId);
        Map<Long, Brand> brands = brandService.getAllByIds(List.of(product.getBrandId()));
        return ProductDetailInfo.of(product, brandOf(product, brands), likeService.count(productId));
    }

    /**
     * 상품 목록. {@code latest}·{@code price_asc} 는 캐시를 먼저 보고, 없으면 DB 에서 읽어 캐시에 저장한다.
     * 캐시 오류는 {@link ProductListCache} 가 miss 로 돌려주므로 여기서는 구분하지 않는다.
     *
     * <p>트랜잭션은 DB 에서 읽는 구간({@link #load})에만 연다. readOnly 트랜잭션은 시작할 때 DB 커넥션을 잡으므로, 메서드
     * 전체에 열면 캐시 왕복(Redis 장애 때는 명령 timeout 까지) 동안 커넥션을 쥐어 Redis 장애가 커넥션 풀 고갈로 번진다.
     *
     * @param brandId 선택 필터. {@code null} 이면 전체, 존재하지 않는 브랜드이면 빈 목록이다.
     * @param sort    {@code latest}, {@code price_asc}, {@code likes_desc}
     */
    public Page<ProductListItemInfo> getList(Long brandId, String sort, int page, int size) {
        ProductSort productSort = ProductSort.from(sort);
        PageRequest pageRequest = PageRequests.of(page, size);
        if (!CACHED_SORTS.contains(productSort)) {
            return load(brandId, productSort, pageRequest);
        }

        ProductListQuery query = new ProductListQuery(brandId, productSort, page, size);
        Optional<Page<ProductListItemInfo>> cached = productListCache.find(query)
            .flatMap(snapshot -> withLikeCounts(snapshot, pageRequest));
        if (cached.isPresent()) {
            return cached.get();
        }
        Page<ProductListItemInfo> loaded = load(brandId, productSort, pageRequest);
        productListCache.save(query, CachedProductListInfo.from(loaded));
        return loaded;
    }

    // DB 경로. 브랜드와 좋아요 수는 목록의 id 를 모아 한 번에 조회해 항목마다 조회하지 않는다.
    // 네 조회를 한 readOnly 트랜잭션(같은 스냅샷)에서 읽어, 동시 삭제로 상품과 집계가 어긋나지 않는다(likeCountOf).
    private Page<ProductListItemInfo> load(Long brandId, ProductSort sort, PageRequest pageRequest) {
        return readOnlyTransaction.execute(status -> {
            Page<Product> products = productService.search(brandId, sort, pageRequest);
            Map<Long, Brand> brands = brandService.getAllByIds(
                products.stream().map(Product::getBrandId).distinct().toList());
            Map<Long, Long> likeCounts = likeService.countByProductIds(
                products.stream().map(Product::getId).toList());

            return products.map(product ->
                ProductListItemInfo.of(product, brandOf(product, brands), likeCountOf(product, likeCounts)));
        });
    }

    // 캐시 hit 경로. 좋아요 수 조회가 삭제 확인을 겸한다: 집계 행이 없는 상품(삭제됨)이 하나라도 있으면
    // 캐시 값을 쓰지 않고 DB 로 다시 읽는다. 무효화가 실패해도 삭제된 상품이 나가지 않고 페이지가 짧아지지도 않는다.
    // 조회 하나라 트랜잭션을 열지 않는다.
    private Optional<Page<ProductListItemInfo>> withLikeCounts(CachedProductListInfo snapshot, PageRequest pageRequest) {
        Map<Long, Long> likeCounts = likeService.countByProductIds(
            snapshot.items().stream().map(CachedProductListInfo.Item::id).toList());
        if (likeCounts.size() != snapshot.items().size()) {
            return Optional.empty();
        }
        List<ProductListItemInfo> items = snapshot.items().stream()
            .map(item -> item.withLikeCount(likeCounts.get(item.id())))
            .toList();
        return Optional.of(new PageImpl<>(items, pageRequest, snapshot.totalElements()));
    }

    /**
     * 상품의 브랜드를 찾는다. 상품이 있는 브랜드는 삭제할 수 없어 항상 있어야 하므로, 없으면 데이터가 깨진 것으로 보고
     * 조용히 넘기지 않고 드러낸다. 브랜드 삭제와 상품 등록이 동시에 일어나는 경쟁에서만 생길 수 있다.
     */
    private Brand brandOf(Product product, Map<Long, Brand> brands) {
        Brand brand = brands.get(product.getBrandId());
        if (brand == null) {
            throw new CoreException(
                ErrorType.INTERNAL_ERROR,
                "상품 " + product.getId() + " 의 브랜드 " + product.getBrandId() + " 을(를) 찾을 수 없습니다."
            );
        }
        return brand;
    }

    /**
     * DB 에서 읽은 노출 상품의 좋아요 수를 찾는다. 노출 상품은 생성과 같은 트랜잭션에서 집계 행을 가지고, 상품 조회와
     * 집계 조회는 같은 읽기 트랜잭션이라 동시 삭제로 어긋나지 않는다. 그래서 행이 없으면 데이터가 깨진 것으로 보고 드러낸다.
     * 집계 테이블 이행 중(product-like-counts.sql 3~4단계)에 옛 앱이 만든 상품에서만 생길 수 있다.
     */
    private long likeCountOf(Product product, Map<Long, Long> likeCounts) {
        Long likeCount = likeCounts.get(product.getId());
        if (likeCount == null) {
            throw new CoreException(
                ErrorType.INTERNAL_ERROR,
                "상품 " + product.getId() + " 의 좋아요 집계를 찾을 수 없습니다."
            );
        }
        return likeCount;
    }
}
