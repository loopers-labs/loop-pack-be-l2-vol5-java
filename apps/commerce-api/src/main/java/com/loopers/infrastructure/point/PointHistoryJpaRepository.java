package com.loopers.infrastructure.point;

import com.loopers.domain.point.PointHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointHistoryJpaRepository extends JpaRepository<PointHistory, Long> {}
