package com.loopers.domain.product;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 목록 결과를 바꾸는 쓰기 트랜잭션 안에서만 올린다(MANDATORY). 다른 잠금을 모두 잡은 뒤 마지막에, 칸 행 하나만 잡는다
@RequiredArgsConstructor
@Component
public class ProductListRevisionService {

    // 한 브랜드의 목록 revision을 나누는 칸 수. 실습 환경에서 한 브랜드에 좋아요가 몰리면 한 행일 때 처리량이 약 2.4배 줄었다
    static final int SLOT_COUNT = 8;

    // 드문 관리자 변경(등록·삭제·가격 수정·브랜드 삭제)이 쓰는 칸
    private static final int ADMIN_SLOT = 0;

    private final ProductListRevisionRepository productListRevisionRepository;

    // 목록에 들어가고 빠지는 상품이 바뀐다(등록·삭제·브랜드 삭제) — 모든 정렬의 목록이 바뀐다
    @Transactional(propagation = Propagation.MANDATORY)
    public void increaseForMembership(Long brandId) {
        productListRevisionRepository.increase(brandId, ADMIN_SLOT, 1, 1, 1);
    }

    // 가격이 바뀐다 — 가격순 목록의 순서만 바뀐다
    @Transactional(propagation = Propagation.MANDATORY)
    public void increaseForPrice(Long brandId) {
        productListRevisionRepository.increase(brandId, ADMIN_SLOT, 0, 1, 0);
    }

    // 좋아요 수가 바뀐다 — 좋아요순 목록의 순서만 바뀐다. 상품 id로 칸을 골라 같은 브랜드의 다른 상품 좋아요끼리 줄 서지 않게 한다
    @Transactional(propagation = Propagation.MANDATORY)
    public void increaseForLikes(Long brandId, Long productId) {
        productListRevisionRepository.increase(brandId, Math.floorMod(productId, SLOT_COUNT), 0, 0, 1);
    }

    // 브랜드 목록은 그 브랜드의 모든 칸의 합, 전체 목록은 모든 브랜드·칸의 합을 쓴다 — 어느 칸이 바뀌어도 합은 커진다
    @Transactional(readOnly = true)
    public long getRevision(Long brandId, ProductSortType sortType) {
        if (brandId == null) {
            return productListRevisionRepository.sumRevisions(sortType);
        }
        return productListRevisionRepository.findRevision(brandId, sortType);
    }
}
