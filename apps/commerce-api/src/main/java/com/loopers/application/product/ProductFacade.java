package com.loopers.application.product;

import com.loopers.domain.brand.BrandService;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.like.LikeService;
import org.springframework.context.ApplicationEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
public class ProductFacade {

    private final BrandService brandService;
    private final ProductService productService;
    private final LikeService likeService;
    private final ApplicationEventPublisher events;

    @Transactional
    public ProductInfo create(Long brandId, String name, long price, int initialStockQuantity) {
        brandService.getForShare(brandId);
        var product = productService.create(brandId, name, price, initialStockQuantity);
        likeService.initializeCount(product.getId());
        events.publishEvent(new PricePageChanged(brandId));
        events.publishEvent(new PopularPageChanged(brandId));
        return ProductInfo.from(product);
    }

    public ProductInfo get(Long id) {
        return ProductInfo.from(productService.get(id));
    }

    public List<ProductInfo> getAll() {
        return productService.findAllActive().stream().map(ProductInfo::from).toList();
    }

    public ProductInfo changeStock(Long id, int quantity) {
        return ProductInfo.from(productService.changeStock(id, quantity));
    }

    @Transactional
    public ProductInfo update(Long id, String name, long price) {
        var product = productService.update(id, name, price);
        events.publishEvent(new PricePageChanged(product.getBrandId()));
        return ProductInfo.from(product);
    }

    @Transactional
    public void delete(Long id) {
        Long brandId = productService.getBrandId(id);
        productService.delete(id);
        events.publishEvent(new PricePageChanged(brandId));
        events.publishEvent(new PopularPageChanged(brandId));
    }
}
