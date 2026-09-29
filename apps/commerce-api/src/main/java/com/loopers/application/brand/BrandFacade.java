package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
public class BrandFacade {

    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;

    @Transactional
    public Brand createBrand(String name) {
        return brandRepository.save(new Brand(name));
    }

    @Transactional(readOnly = true)
    public Brand getBrand(Long id) {
        return findActiveBrand(id);
    }

    /**
     * 관리자 목록은 삭제된 브랜드도 포함한다. 무엇이 삭제되었는지 확인하는 것도 관리자의 역할이다.
     */
    @Transactional(readOnly = true)
    public List<Brand> getBrandsForAdmin(int page, int size) {
        return brandRepository.findAllForAdmin(page, size);
    }

    @Transactional
    public Brand updateBrand(Long id, String name) {
        Brand brand = findActiveBrand(id);
        brand.changeName(name);
        return brand;
    }

    /**
     * 삭제되지 않은 연결 상품이 하나라도 있으면 거절한다. 재고 0인 상품도 포함한다.
     * 논리 삭제이므로 기존 상품·주문의 참조는 그대로 유지된다.
     */
    @Transactional
    public void deleteBrand(Long id) {
        Brand brand = findActiveBrand(id);

        if (productRepository.existsActiveByBrandId(id)) {
            throw new CoreException(ErrorType.CONFLICT, "삭제되지 않은 연결 상품이 남아 있어 브랜드를 삭제할 수 없습니다.");
        }
        brand.delete();
    }

    /**
     * 존재하며 삭제되지 않은 브랜드만 반환한다.
     * 브랜드 CRUD 가 늘어나면 이 책임을 BrandService 로 옮긴다.
     */
    private Brand findActiveBrand(Long id) {
        Brand brand = brandRepository.findById(id)
            .orElseThrow(() -> notFound(id));

        if (brand.getDeletedAt() != null) {
            throw notFound(id);
        }
        return brand;
    }

    private CoreException notFound(Long id) {
        return new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 브랜드를 찾을 수 없습니다.");
    }
}
