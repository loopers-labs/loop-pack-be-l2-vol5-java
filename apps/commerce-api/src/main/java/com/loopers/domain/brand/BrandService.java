package com.loopers.domain.brand;

import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;
import com.loopers.domain.common.PageCondition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@RequiredArgsConstructor
@Component
public class BrandService {

    private final BrandRepository brandRepository;

    @Transactional(readOnly = true)
    public Brand getBrand(Long id) {
        return brandRepository.findActive(id)
            .orElseThrow(() -> new DomainException(DomainErrorType.NOT_FOUND, "[brandId = " + id + "] 브랜드를 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public List<Brand> getBrands(Collection<Long> ids) {
        return brandRepository.findAll(ids);
    }

    @Transactional
    public Brand register(String name) {
        return brandRepository.save(new Brand(name));
    }

    @Transactional
    public Brand update(Long id, String name) {
        Brand brand = getBrand(id);
        brand.update(name);
        return brand;
    }

    // 연결된 상품 확인은 application이 맡고, 여기서는 삭제 상태로만 바꾼다(DEL-001, 3-3)
    @Transactional
    public void delete(Long id) {
        getBrand(id).delete();
    }

    @Transactional(readOnly = true)
    public List<Brand> getBrands(PageCondition page) {
        return brandRepository.findActive(page);
    }

    @Transactional(readOnly = true)
    public long countBrands() {
        return brandRepository.countActive();
    }
}
