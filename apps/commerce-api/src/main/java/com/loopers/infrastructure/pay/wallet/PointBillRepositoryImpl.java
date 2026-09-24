package com.loopers.infrastructure.pay.wallet;

import com.loopers.domain.pay.wallet.PointBill;
import com.loopers.domain.pay.wallet.PointBillRepository;
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
