package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class BrandService {

    private final BrandRepository brandRepository;

    @Transactional(readOnly = true)
    public BrandModel getActiveBrand(Long id) {
        return brandRepository.findActiveById(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 브랜드를 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public BrandModel getBrand(Long id) {
        return brandRepository.findById(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 브랜드를 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public List<BrandModel> getBrands() {
        return brandRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Map<Long, String> getBrandNames(List<Long> ids) {
        return brandRepository.findAllByIds(ids).stream()
            .collect(Collectors.toMap(BrandModel::getId, BrandModel::getName));
    }

    public void validateActiveBrand(Long id) {
        if (brandRepository.findActiveById(id).isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "[id = " + id + "] 존재하지 않거나 삭제된 브랜드입니다.");
        }
    }

    @Transactional
    public BrandModel create(String name) {
        return brandRepository.save(new BrandModel(name));
    }

    @Transactional
    public BrandModel update(Long id, String name) {
        BrandModel brand = getActiveBrand(id);
        brand.update(name);
        return brandRepository.save(brand);
    }

    @Transactional
    public void delete(Long id) {
        BrandModel brand = getBrand(id);
        brand.delete();
        brandRepository.save(brand);
    }
}
