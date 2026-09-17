package com.loopers.infrastructure.point;

import com.loopers.domain.point.PointHistoryModel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointHistoryJpaRepository extends JpaRepository<PointHistoryModel, Long> {
}
