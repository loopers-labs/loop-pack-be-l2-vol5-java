package com.loopers.application.product;

import com.loopers.domain.brand.BrandService;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSortType;
import lombok.RequiredArgsConstructor;
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

    public ProductInfo getProduct(Long productId) {
        ProductModel product = productService.getActiveProduct(productId);
        String brandName = brandService.getBrand(product.getBrandId()).getName();
        long likeCount = likeService.countByProduct(productId);
        return ProductInfo.from(product, brandName, likeCount);
    }

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
        return ProductAdminInfo.from(productService.create(brandId, name, price, stock));
    }

    public ProductAdminInfo updateProduct(Long productId, String name, Long price) {
        return ProductAdminInfo.from(productService.update(productId, name, price));
    }

    public ProductAdminInfo changeStock(Long productId, int quantity) {
        return ProductAdminInfo.from(productService.changeStock(productId, quantity));
    }

    public void deleteProduct(Long productId) {
        productService.delete(productId);
    }
}
