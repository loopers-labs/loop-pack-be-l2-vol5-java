package com.loopers.infrastructure.purchase;

import com.loopers.domain.purchase.PurchaseActivityConflict;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PurchaseActivityConflictJpaRepository extends JpaRepository<PurchaseActivityConflict, Long> {

    List<PurchaseActivityConflict> findAllByOrderIdOrderByIdAsc(Long orderId);
}
