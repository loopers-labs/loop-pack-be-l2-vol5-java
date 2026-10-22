package com.loopers.application.product;

import com.loopers.domain.brand.BrandService;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSortType;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class ProductFacade {

    private final ProductService productService;
    private final BrandService brandService;
    private final LikeService likeService;
    private final ProductCachePort productCachePort;

    public ProductInfo getProduct(Long productId) {
        ProductModel product = productService.getActiveProduct(productId);
        String brandName = brandService.getBrand(product.getBrandId()).getName();
        long likeCount = likeService.countByProduct(productId);
        return ProductInfo.from(product, brandName, likeCount);
    }

    @Cacheable(
        cacheNames = "products",
        key = "(#brandId == null ? 'all' : #brandId) + ':' + #sortType.name() + ':' + #page + ':' + #size"
    )
    public ProductListInfo getProducts(Long brandId, ProductSortType sortType, int page, int size) {
        Page<ProductModel> products = productService.getActiveProducts(brandId, sortType, page, size);

        List<Long> productIds = products.getContent().stream().map(ProductModel::getId).toList();
        Map<Long, Long> likeCounts = likeService.countByProducts(productIds);
        List<Long> brandIds = products.getContent().stream().map(ProductModel::getBrandId).distinct().toList();
        Map<Long, String> brandNames = brandService.getBrandNames(brandIds);

        List<ProductInfo> items = products.getContent().stream()
            .map(product -> ProductInfo.from(
                product,
                brandNames.get(product.getBrandId()),
                likeCounts.getOrDefault(product.getId(), 0L)
            ))
            .toList();
        return new ProductListInfo(items, page, size, products.getTotalElements());
    }

    public ProductListAdminInfo getProductsForAdmin(int page, int size) {
        Page<ProductModel> products = productService.getProductsForAdmin(page, size);
        List<ProductAdminInfo> items = products.getContent().stream().map(ProductAdminInfo::from).toList();
        return new ProductListAdminInfo(items, page, size, products.getTotalElements());
    }

    public ProductAdminInfo getProductForAdmin(Long productId) {
        return ProductAdminInfo.from(productService.getProduct(productId));
    }

    public ProductAdminInfo createProduct(Long brandId, String name, Long price, int stock) {
        brandService.validateActiveBrand(brandId);
        ProductAdminInfo result = ProductAdminInfo.from(productService.create(brandId, name, price, stock));
        productCachePort.evictByBrandId(brandId);
        return result;
    }

    public ProductAdminInfo updateProduct(Long productId, String name, Long price) {
        ProductModel product = productService.getProduct(productId);
        ProductAdminInfo result = ProductAdminInfo.from(productService.update(productId, name, price));
        productCachePort.evictByBrandId(product.getBrandId());
        return result;
    }

    public ProductAdminInfo changeStock(Long productId, int quantity) {
        ProductModel product = productService.getProduct(productId);
        ProductAdminInfo result = ProductAdminInfo.from(productService.changeStock(productId, quantity));
        productCachePort.evictByBrandId(product.getBrandId());
        return result;
    }

    public void deleteProduct(Long productId) {
        ProductModel product = productService.getProduct(productId);
        productService.delete(productId);
        productCachePort.evictByBrandId(product.getBrandId());
    }
}
