package com.loopers.application.product;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductListRevisionService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRevisionService;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSortType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

@RequiredArgsConstructor
@Component
public class ProductFacade {
    // 재사용이 많은 앞 페이지(0~4)만 캐시한다. 뒤 페이지까지 캐시하면 순서가 밀릴 때 버려지는 키만 늘어난다
    private static final int CACHED_PAGE_COUNT = 5;

    private final ProductService productService;
    private final BrandService brandService;
    private final LikeService likeService;
    private final ProductListRevisionService productListRevisionService;
    private final ProductRevisionService productRevisionService;
    private final ProductListCache productListCache;
    private final ProductCache productCache;

    public ProductInfo getProduct(Long id) {
        ProductModel product = productService.getProduct(id);
        BrandModel brand = brandService.getBrand(product.getBrandId());
        long likeCount = likeService.countActiveByProduct(id);
        return ProductInfo.from(product, brand.getName(), likeCount);
    }

    public ProductAdminInfo getProductForAdmin(Long id) {
        ProductModel product = productService.getProductForAdmin(id);
        return ProductAdminInfo.from(product);
    }

    // revision과 목록·상품을 같은 읽기 트랜잭션(같은 스냅숏)에서 읽는다 — 이전 결과가 새 revision의 키에 저장되지 않게
    @Transactional(readOnly = true)
    public Page<ProductInfo> getProducts(Long brandId, ProductSortType sortType, Pageable pageable) {
        if (pageable.getPageNumber() >= CACHED_PAGE_COUNT) {
            Page<ProductModel> products = productService.getProducts(brandId, sortType, pageable);
            return products.map(toProductInfo(products.getContent()));
        }

        long listRevision = productListRevisionService.getRevision(brandId, sortType);
        ProductListCacheKey key = new ProductListCacheKey(
            brandId, listRevision, sortType, pageable.getPageNumber(), pageable.getPageSize()
        );
        Optional<ProductIdPage> cachedIds = productListCache.find(key);
        if (cachedIds.isPresent()) {
            List<ProductSnapshot> snapshots = getSnapshots(cachedIds.get().productIds());
            return new PageImpl<>(toProductInfos(snapshots), pageable, cachedIds.get().totalElements());
        }

        Page<ProductModel> products = productService.getProducts(brandId, sortType, pageable);
        List<ProductSnapshot> snapshots = toSnapshots(products.getContent());
        List<Long> productIds = snapshots.stream().map(ProductSnapshot::id).toList();
        productCache.saveAll(productRevisionService.getRevisions(productIds), snapshots);
        productListCache.save(key, new ProductIdPage(productIds, products.getTotalElements()));
        return new PageImpl<>(toProductInfos(snapshots), pageable, products.getTotalElements());
    }

    // 상품 캐시에서 찾고, 없는 상품만 DB에서 읽어 채운다. 목록의 상품 id 순서를 그대로 지킨다
    private List<ProductSnapshot> getSnapshots(List<Long> productIds) {
        Map<Long, Long> revisions = productRevisionService.getRevisions(productIds);
        Map<Long, ProductSnapshot> found = new HashMap<>(productCache.findAll(revisions));
        List<Long> missingIds = productIds.stream().filter(id -> !found.containsKey(id)).toList();
        if (!missingIds.isEmpty()) {
            List<ProductSnapshot> loaded = toSnapshots(productService.getActiveProducts(missingIds));
            productCache.saveAll(revisions, loaded);
            loaded.forEach(snapshot -> found.put(snapshot.id(), snapshot));
        }
        return productIds.stream().map(found::get).filter(Objects::nonNull).toList();
    }

    private List<ProductSnapshot> toSnapshots(List<ProductModel> products) {
        Map<Long, Long> likeCounts = likeService.countActiveByProducts(products.stream().map(ProductModel::getId).toList());
        return products.stream()
            .map(product -> ProductSnapshot.from(product, likeCounts.getOrDefault(product.getId(), 0L)))
            .toList();
    }

    // 브랜드 이름은 캐시하지 않고 조회할 때마다 붙인다
    private List<ProductInfo> toProductInfos(List<ProductSnapshot> snapshots) {
        List<Long> brandIds = snapshots.stream().map(ProductSnapshot::brandId).distinct().toList();
        Map<Long, BrandModel> brands = brandService.getBrandsByIds(brandIds);
        return snapshots.stream()
            .map(snapshot -> ProductInfo.from(snapshot, brands.get(snapshot.brandId()).getName()))
            .toList();
    }

    public Page<ProductAdminInfo> getProductsForAdmin(Pageable pageable) {
        return productService.getProductsForAdmin(pageable).map(ProductAdminInfo::from);
    }

    public List<ProductInfo> getProductsByIds(List<Long> productIds) {
        List<ProductModel> products = productService.getActiveProducts(productIds);
        return products.stream().map(toProductInfo(products)).toList();
    }

    private Function<ProductModel, ProductInfo> toProductInfo(List<ProductModel> products) {
        List<Long> productIds = products.stream().map(ProductModel::getId).toList();
        List<Long> brandIds = products.stream().map(ProductModel::getBrandId).distinct().toList();
        Map<Long, Long> likeCounts = likeService.countActiveByProducts(productIds);
        Map<Long, BrandModel> brands = brandService.getBrandsByIds(brandIds);
        return product -> ProductInfo.from(
            product,
            brands.get(product.getBrandId()).getName(),
            likeCounts.getOrDefault(product.getId(), 0L)
        );
    }

    public ProductAdminInfo createProduct(Long brandId, String name, Long price, int stock) {
        brandService.getBrand(brandId);
        ProductModel product = productService.createProduct(brandId, name, price, stock);
        return ProductAdminInfo.from(product);
    }

    public ProductAdminInfo updateProduct(Long id, String name, Long price) {
        ProductModel product = productService.updateProduct(id, name, price);
        return ProductAdminInfo.from(product);
    }

    public ProductAdminInfo changeStock(Long id, int quantity) {
        ProductModel product = productService.changeStock(id, quantity);
        return ProductAdminInfo.from(product);
    }

    public void deleteProduct(Long id) {
        productService.deleteProduct(id);
    }
}
