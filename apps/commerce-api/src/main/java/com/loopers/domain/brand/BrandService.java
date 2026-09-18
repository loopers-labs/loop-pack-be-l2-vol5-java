package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class BrandService {

    private final BrandRepository brandRepository;

    @Transactional(readOnly = true)
    public BrandModel getBrand(Long id) {
        return brandRepository.findActiveById(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 브랜드를 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public BrandModel getBrandForAdmin(Long id) {
        return brandRepository.findById(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 브랜드를 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public Page<BrandModel> getBrands(Pageable pageable) {
        return brandRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Map<Long, BrandModel> getBrandsByIds(List<Long> ids) {
        return brandRepository.findAllByIds(ids).stream()
            .collect(Collectors.toMap(BrandModel::getId, Function.identity()));
    }

    @Transactional
    public BrandModel createBrand(String name) {
        return brandRepository.save(new BrandModel(name));
    }

    @Transactional
    public BrandModel updateBrand(Long id, String name) {
        BrandModel brand = getBrandForAdmin(id);
        brand.updateName(name);
        return brand;
    }

    @Transactional
    public void deleteBrand(Long id) {
        BrandModel brand = getBrandForAdmin(id);
        brand.delete();
    }
}
