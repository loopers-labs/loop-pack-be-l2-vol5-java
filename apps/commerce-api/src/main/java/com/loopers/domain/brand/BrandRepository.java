package com.loopers.domain.brand;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface BrandRepository {
    Optional<Brand> findById(long id);

    /**
     * 브랜드를 확인한 뒤 변경하는 쓰기 유스케이스에서 사용한다.
     * 호출자는 전체 작업을 트랜잭션으로 묶어 잠금을 작업 종료까지 유지해야 한다.
     */
    Optional<Brand> findByIdForUpdate(long id);

    /** 신규 브랜드를 저장한다. 기존 브랜드의 변경은 잠금 조회와 같은 트랜잭션에서 저장한다. */
    Brand save(Brand brand);

    Page<Brand> findAll(Pageable pageable);
}
