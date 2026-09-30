package com.loopers.brand.adapter.out.persistence;

import com.loopers.brand.domain.BrandModel;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 저장과 식별자 조회 같은 기본 동작만 맡는다. 조건이 붙는 조회는 BrandPersistenceAdapter이 QueryDSL로 쓴다.
 */
public interface BrandJpaRepository extends JpaRepository<BrandModel, Long> {
}
