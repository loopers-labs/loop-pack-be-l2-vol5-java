package com.loopers.domain.catalog;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class BrandService {

    private final BrandRepository brandRepository;

    /** 존재하고 삭제되지 않은 브랜드. 없음·삭제됨 모두 ER-03 BRAND_NOT_FOUND (고객 조회, 수정·삭제·상품 생성의 대상). */
    public BrandModel getActive(Long brandId) {
        BrandModel brand = get(brandId);
        if (brand.isDeleted()) {
            throw new CoreException(ErrorType.BRAND_NOT_FOUND, "[id = " + brandId + "] 삭제된 브랜드입니다.");
        }
        return brand;
    }

    /** 존재하는 브랜드. 삭제 여부 무관 (ASM-15, 관리자 상세). */
    public BrandModel get(Long brandId) {
        return brandRepository.find(brandId)
            .orElseThrow(() -> new CoreException(ErrorType.BRAND_NOT_FOUND, "[id = " + brandId + "] 브랜드를 찾을 수 없습니다."));
    }

    /** 상품 응답에 실을 브랜드 정보. 삭제된 브랜드도 포함한다 (관리자 상품 목록의 삭제 상품이 가리킬 수 있음). */
    public Map<Long, BrandModel> getByIds(Collection<Long> brandIds) {
        return brandRepository.findByIds(brandIds).stream()
            .collect(Collectors.toMap(BrandModel::getId, Function.identity()));
    }

    public PageResult<BrandModel> listAll(PageQuery query) {
        return brandRepository.findPage(query);
    }

    public BrandModel create(String name) {
        return brandRepository.save(new BrandModel(name));
    }

    public BrandModel update(Long brandId, String name) {
        BrandModel brand = getActive(brandId);
        brand.update(name);
        return brand;
    }

    /** ST-01 ACTIVE → DELETED. INV-10 의 상품 유무 검사는 Facade 가 ProductService 로 같은 트랜잭션에서 한다 (DR-04). */
    public void delete(Long brandId) {
        BrandModel brand = getActive(brandId);
        brand.delete();
    }
}
