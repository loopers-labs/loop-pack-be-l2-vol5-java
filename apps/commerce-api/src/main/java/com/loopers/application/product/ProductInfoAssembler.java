package com.loopers.application.product;

import com.loopers.application.brand.BrandInfo;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.product.ProductWithLikeCount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class ProductInfoAssembler {
    private final BrandService brandService;

    public List<ProductInfo> assemble(List<ProductWithLikeCount> rows) {
        List<Long> brandIds = rows.stream().map(row -> row.product().getBrandId()).distinct().toList();
        Map<Long, BrandInfo> brands = brandService.getBrands(brandIds).stream()
            .collect(Collectors.toMap(Brand::getId, BrandInfo::from));
        return rows.stream()
            .map(row -> ProductInfo.of(row.product(), brands.get(row.product().getBrandId()), row.likeCount()))
            .toList();
    }
}
