package com.loopers.infrastructure.persistence.pay.repository;

import com.loopers.domain.pay.model.PointBill;
import com.loopers.domain.pay.repository.PointBillRepository;
import com.loopers.infrastructure.persistence.pay.entity.PointBillEntityMapper;
import com.loopers.infrastructure.persistence.pay.jpa.PointBillJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
// 포인트 기록 저장소 구현체
public class PointBillRepositoryImpl implements PointBillRepository {
    private final PointBillJpaRepository pointBillJpaRepository;
    private final PointBillEntityMapper mapper;

    @Override
    public PointBill save(PointBill pointBill) {
        return mapper.toDomain(pointBillJpaRepository.save(mapper.toNewEntity(pointBill)));
    }
}
