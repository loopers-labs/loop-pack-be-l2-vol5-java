package com.loopers.application.brand.query;

import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 브랜드 조회 유스케이스 (DR-31). */
@RequiredArgsConstructor
@Component
public class BrandReader {
    private final UserService userService;
    private final BrandQueryRepository brandQueryRepository;

    /** FR-BRAND-01 브랜드 상세 조회 (고객). 없음·삭제됨 모두 ER-03 BRAND_NOT_FOUND. */
    @Transactional(readOnly = true)
    public BrandView.Summary getBrand(Long requesterId, Long brandId) {
        userService.getUser(requesterId);
        return brandQueryRepository.findActive(brandId)
            .orElseThrow(() -> new CoreException(ErrorType.BRAND_NOT_FOUND, "[id = " + brandId + "] 브랜드를 찾을 수 없습니다."));
    }

    /** FR-ADMIN-BRAND-01 브랜드 목록 (관리자). 삭제 포함, 최신순 (ASM-15, ASM-20). */
    @Transactional(readOnly = true)
    public PageResult<BrandView.Admin> listBrandsForAdmin(Long requesterId, PageQuery query) {
        userService.getAdmin(requesterId);
        return brandQueryRepository.findPage(query);
    }

    /** FR-ADMIN-BRAND-03 브랜드 상세 (관리자). 삭제 여부 무관. */
    @Transactional(readOnly = true)
    public BrandView.Admin getBrandForAdmin(Long requesterId, Long brandId) {
        userService.getAdmin(requesterId);
        return brandQueryRepository.find(brandId)
            .orElseThrow(() -> new CoreException(ErrorType.BRAND_NOT_FOUND, "[id = " + brandId + "] 브랜드를 찾을 수 없습니다."));
    }
}
