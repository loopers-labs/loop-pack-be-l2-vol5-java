package com.loopers.domain.brand;

import java.util.Optional;

public interface BrandRepository {

    /**
     * 저장된 객체를 반환한다. 여러 변경을 묶는 트랜잭션과 필요한 행 잠금은 호출 유스케이스가 보장한다.
     */
    Brand save(Brand brand);

    /**
     * 재삭제 판단을 위해 삭제된 브랜드도 포함하여 조회한다.
     * 이 조회 계약만으로 DB 행 잠금이나 변경의 저장을 보장하지 않는다.
     */
    Optional<Brand> findById(long brandId);

    Optional<Brand> lockById(long brandId);
}
