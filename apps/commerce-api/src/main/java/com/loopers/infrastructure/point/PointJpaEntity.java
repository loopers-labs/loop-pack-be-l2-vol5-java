package com.loopers.infrastructure.point;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "points")
public class PointJpaEntity {
    @Id
    private Long userId;
    @Column(nullable = false)
    private long balance;
    protected PointJpaEntity() { }
}
