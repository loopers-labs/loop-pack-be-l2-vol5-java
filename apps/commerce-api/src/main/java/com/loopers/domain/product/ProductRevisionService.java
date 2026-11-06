package com.loopers.domain.product;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 상품 캐시에 담는 값을 바꾸는 쓰기 트랜잭션 안에서만 올린다(MANDATORY). 상품 행을 잠근 뒤, 목록 revision보다 먼저 부른다
@RequiredArgsConstructor
@Component
public class ProductRevisionService {

    private final ProductRevisionRepository productRevisionRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void increase(Long productId) {
        productRevisionRepository.increase(productId);
    }

    // 여러 상품은 상품 id 오름차순으로 잠근다 — 두 트랜잭션이 서로 반대 순서로 revision 행을 잡지 않게
    @Transactional(propagation = Propagation.MANDATORY)
    public void increaseAll(Collection<Long> productIds) {
        productIds.stream().distinct().sorted().forEach(productRevisionRepository::increase);
    }

    // 요청한 상품 순서대로, revision 행이 없는 상품은 0으로 돌려준다
    @Transactional(readOnly = true)
    public Map<Long, Long> getRevisions(List<Long> productIds) {
        Map<Long, Long> found = productIds.isEmpty() ? Map.of() : productRevisionRepository.findRevisions(productIds);
        Map<Long, Long> revisions = new LinkedHashMap<>();
        productIds.forEach(id -> revisions.put(id, found.getOrDefault(id, 0L)));
        return revisions;
    }
}
