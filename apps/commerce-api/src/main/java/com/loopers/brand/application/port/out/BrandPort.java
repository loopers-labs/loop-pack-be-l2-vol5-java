package com.loopers.brand.application.port.out;

import com.loopers.brand.domain.BrandModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BrandPort {

    BrandModel save(BrandModel brand);

    /**
     * BRD-03: 삭제되지 않은 브랜드만 찾는다.
     */
    Optional<BrandModel> findActiveById(Long id);

    /**
     * 삭제되지 않은 브랜드를 쓰기 잠금(SELECT … FOR UPDATE)으로 찾는다. 잠금은 호출한 트랜잭션이 끝날 때 풀린다 (ADR-W3-05).
     */
    Optional<BrandModel> findActiveByIdForUpdate(Long id);

    Page<BrandModel> findActive(Pageable pageable);

    /**
     * 조회 조합용. 삭제 여부와 관계없이 식별자로 한 번에 찾는다.
     */
    List<BrandModel> findAllByIds(Collection<Long> ids);
}
