package com.loopers.user.infrastructure;

import com.loopers.user.domain.Point;
import com.loopers.user.domain.PointRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
public class PointRepositoryAdapter implements PointRepository {
    private final PointJpaRepository jpa;
    private final EntityManager entityManager;
    public PointRepositoryAdapter(PointJpaRepository jpa, EntityManager entityManager) { this.jpa = jpa; this.entityManager = entityManager; }
    public Point save(Point point) { if (point.getId() == 0L) entityManager.persist(point); else jpa.save(point); return point; }
    public Optional<Point> findByUserId(Long userId) { return jpa.findByUserId(userId); }
    public Optional<Point> findForOrderByUserId(Long userId) { return jpa.findForOrderByUserId(userId); }
}
