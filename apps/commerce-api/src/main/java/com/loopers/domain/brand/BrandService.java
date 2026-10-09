package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@RequiredArgsConstructor
@Component
public class BrandService {
    private final BrandRepository brandRepository;

    @Transactional
    public BrandModel create(String name, String description) {
        BrandModel brand = new BrandModel(name, description);
        if (brandRepository.existsActiveByNameIgnoreCase(brand.getName())) {
            throw new CoreException(ErrorType.CONFLICT, "이미 사용 중인 브랜드 이름입니다.");
        }
        return brandRepository.save(brand);
    }

    @Transactional(readOnly = true)
    public BrandModel get(Long id) {
        return brandRepository.find(id)
            .filter(brand -> brand.getDeletedAt() == null)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 브랜드를 찾을 수 없습니다."));
    }

    @Transactional
    public BrandModel getForShare(Long id) {
        return brandRepository.findForShare(id)
            .filter(brand -> brand.getDeletedAt() == null)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 브랜드를 찾을 수 없습니다."));
    }

    @Transactional
    public BrandModel getForUpdate(Long id) {
        return brandRepository.findForUpdate(id)
            .filter(brand -> brand.getDeletedAt() == null)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 브랜드를 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public List<BrandModel> getAllActiveByIds(Collection<Long> ids) {
        return brandRepository.findActiveByIds(ids);
    }

    @Transactional(readOnly = true)
    public List<BrandModel> getAllActive() {
        return brandRepository.findAllActive();
    }

    @Transactional
    public BrandModel update(Long id, String name, String description) {
        BrandModel brand = getForUpdate(id);
        BrandModel candidate = new BrandModel(name, description);
        if (!brand.getName().equalsIgnoreCase(candidate.getName())
            && brandRepository.existsActiveByNameIgnoreCase(candidate.getName())) {
            throw new CoreException(ErrorType.CONFLICT, "이미 사용 중인 브랜드 이름입니다.");
        }
        brand.update(name, description);
        return brandRepository.save(brand);
    }

    @Transactional
    public void delete(Long id) {
        BrandModel brand = getForUpdate(id);
        brand.markDeleted();
        brandRepository.save(brand);
    }
}
