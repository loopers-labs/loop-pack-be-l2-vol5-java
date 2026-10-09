package com.loopers.user.infrastructure;

import com.loopers.user.domain.Point;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

interface PointJpaRepository extends JpaRepository<Point, Long> {
    Optional<Point> findForOrderByUserId(Long userId);

    Optional<Point> findByUserId(Long userId);
}
