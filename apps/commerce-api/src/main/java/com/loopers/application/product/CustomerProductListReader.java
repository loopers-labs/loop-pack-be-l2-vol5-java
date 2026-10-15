package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class CustomerProductListReader {

    private final BrandRepository brandRepository;
    private final LikeRepository likeRepository;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public List<CustomerProductInfo> read(Long brandId, ProductSort sort, int page, int size) {
        List<Product> pageProducts = productRepository.findActivePage(brandId, sort, page, size);
        List<Long> brandIds = pageProducts.stream().map(Product::getBrandId).distinct().toList();
        Map<Long, Brand> brandsById = brandRepository.findAllByIds(brandIds).stream()
            .collect(Collectors.toMap(Brand::getId, Function.identity()));
        List<Long> productIds = pageProducts.stream().map(Product::getId).toList();
        Map<Long, Long> likeCountsByProductId = likeRepository.countByProductIds(productIds);
        return pageProducts.stream()
            .map(product -> CustomerProductInfo.from(
                product,
                brandsById.get(product.getBrandId()),
                likeCountsByProductId.getOrDefault(product.getId(), 0L)
            ))
            .toList();
    }
}
