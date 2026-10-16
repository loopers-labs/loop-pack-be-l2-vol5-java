package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
public class ProductListCacheWarmer {

    private static final List<String> SORTS = List.of("latest", "price_asc", "likes_desc");
    private static final int FIRST_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;

    private final ProductFacade productFacade;
    private final BrandRepository brandRepository;

    // 정렬마다 전체 목록과 활성 브랜드 목록의 첫 페이지를 기본 크기로 채운다.
    // 고객 목록 조회 경로를 그대로 사용하므로 이미 캐시된 조건은 DB를 다시 읽지 않는다.
    public Result warmUp() {
        List<Long> brandIds = new ArrayList<>();
        brandIds.add(null);
        try {
            brandRepository.findAllActive().stream().map(Brand::getId).forEach(brandIds::add);
        } catch (RuntimeException exception) {
            log.warn("상품 목록 캐시 워밍업 대상 브랜드를 읽지 못했습니다. cause={}", exception.toString());
            return new Result(0, 1);
        }

        int warmed = 0;
        int failed = 0;
        for (String sort : SORTS) {
            for (Long brandId : brandIds) {
                try {
                    productFacade.getCustomerList(brandId, FIRST_PAGE, DEFAULT_SIZE, sort);
                    warmed++;
                } catch (RuntimeException exception) {
                    failed++;
                    log.warn("상품 목록 캐시를 채우지 못했습니다. brandId={}, sort={}, cause={}",
                        brandId, sort, exception.toString());
                }
            }
        }
        return new Result(warmed, failed);
    }

    public record Result(int warmed, int failed) {
    }
}
