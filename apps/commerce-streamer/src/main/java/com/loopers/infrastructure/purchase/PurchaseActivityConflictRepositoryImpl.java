package com.loopers.infrastructure.purchase;

import com.loopers.domain.purchase.PurchaseActivityConflict;
import com.loopers.domain.purchase.PurchaseActivityConflictRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class PurchaseActivityConflictRepositoryImpl implements PurchaseActivityConflictRepository {

    private final PurchaseActivityConflictJpaRepository purchaseActivityConflictJpaRepository;

    /** saveAndFlush — unique 판정을 commit까지 미루지 않고 이 호출에서 받는다. */
    @Override
    public PurchaseActivityConflict insert(PurchaseActivityConflict entity) {
        return purchaseActivityConflictJpaRepository.saveAndFlush(entity);
    }

    @Override
    public List<PurchaseActivityConflict> findAllByOrderId(Long orderId) {
        return purchaseActivityConflictJpaRepository.findAllByOrderIdOrderByIdAsc(orderId);
    }
}
