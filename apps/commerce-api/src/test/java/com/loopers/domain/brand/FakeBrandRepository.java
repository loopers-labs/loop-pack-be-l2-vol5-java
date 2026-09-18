package com.loopers.domain.brand;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** DB 없이 브랜드 서비스의 협력을 확인하기 위한 저장 구현. */
public class FakeBrandRepository implements BrandRepository {

    private final Map<Long, Brand> brands = new LinkedHashMap<>();
    private final Set<Long> brandIdsWithActiveProduct = new HashSet<>();
    private long sequence = 0L;

    @Override
    public Brand save(Brand brand) {
        if (brand.getId() == null || brand.getId() == 0L) {
            ReflectionTestUtils.setField(brand, "id", ++sequence);
        }
        brands.put(brand.getId(), brand);
        return brand;
    }

    @Override
    public Optional<Brand> findActive(Long brandId) {
        return Optional.ofNullable(brands.get(brandId)).filter(brand -> !brand.isDeleted());
    }

    @Override
    public Page<Brand> findActive(Pageable pageable) {
        List<Brand> active = brands.values().stream().filter(brand -> !brand.isDeleted()).toList();
        return new PageImpl<>(active, pageable, active.size());
    }

    @Override
    public boolean hasActiveProduct(Long brandId) {
        return brandIdsWithActiveProduct.contains(brandId);
    }

    public void addActiveProductTo(Long brandId) {
        brandIdsWithActiveProduct.add(brandId);
    }
}
