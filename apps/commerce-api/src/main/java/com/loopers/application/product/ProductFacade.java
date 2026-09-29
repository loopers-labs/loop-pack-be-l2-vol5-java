package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
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

    private final ProductService productService;
    private final BrandRepository brandRepository;
    private final LikeService likeService;

    public List<ProductInfo> getProducts(Long brandId, String sort, int page, int size) {
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
    public int changeStock(Long productId, int quantity) {
        return productService.changeStock(productId, quantity).getStock().getQuantity();
    }

    /**
     * 브랜드가 존재하고 삭제되지 않았는지 확인한 뒤 등록한다.
     */
    @Transactional
    public ProductInfo createProduct(Long brandId, String name, long price) {
        Brand brand = requireActiveBrand(brandId);
        Product product = productService.createProduct(brandId, name, new Price(price));

        return ProductInfo.of(product, brand.getName(), 0L);
    }

    @Transactional
    public ProductInfo updateProduct(Long productId, String name, long price) {
        Product product = productService.updateProduct(productId, name, new Price(price));
        String brandName = brandRepository.findById(product.getBrandId())
            .map(Brand::getName)
            .orElse(null);

        return ProductInfo.of(product, brandName, likeService.countByProductId(product.getId()));
    }

    @Transactional
    public void deleteProduct(Long productId) {
        productService.deleteProduct(productId);
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

        return brandIds.stream()
            .map(brandId -> brandRepository.findById(brandId)
                .map(brand -> Map.entry(brandId, brand.getName())))
            .flatMap(Optional::stream)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
