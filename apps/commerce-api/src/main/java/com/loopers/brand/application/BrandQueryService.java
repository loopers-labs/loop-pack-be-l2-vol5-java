package com.loopers.brand.application;

import com.loopers.brand.application.port.in.BrandInfo;
import com.loopers.brand.application.port.out.BrandPort;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 브랜드 조회. 지킬 규칙이 없어 입력 포트 없이 웹 어댑터가 직접 부르되, 저장소는 출력 포트로만 읽는다.
 */
@RequiredArgsConstructor
@Component
@Transactional(readOnly = true)
public class BrandQueryService {

    private final BrandPort brandPort;

    public BrandInfo getBrand(Long brandId) {
        return brandPort.findActiveById(brandId)
            .map(BrandInfo::from)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[brandId = " + brandId + "] 브랜드를 찾을 수 없습니다."));
    }

    public Page<BrandInfo> getBrands(Pageable pageable) {
        return brandPort.findActive(pageable).map(BrandInfo::from);
    }
}
