package com.loopers.domain;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * ID와 생성·수정 시각은 자동으로 채우고, 삭제 시각은 도메인이 정한 값을 반영한다.
 * 재사용성을 위해 이 외의 컬럼이나 동작은 추가하지 않는다.
 */
@MappedSuperclass
@Getter
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private ZonedDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt;

    @Column(name = "deleted_at")
    private ZonedDateTime deletedAt;

    /**
     * 엔티티의 유효성을 검증한다.
     * 이 메소드는 PrePersist 및 PreUpdate 시점에 호출된다.
     */
    protected void guard() {}

    @PrePersist
    private void prePersist() {
        guard();

        ZonedDateTime now = ZonedDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    private void preUpdate() {
        guard();

        this.updatedAt = ZonedDateTime.now();
    }

    /**
     * 이미 결정된 도메인 삭제 시각을 영속성 모델에도 동일하게 반영한다.
     */
    public void delete(ZonedDateTime deletedAt) {
        if (this.deletedAt == null) {
            this.deletedAt = Objects.requireNonNull(deletedAt, "deletedAt");
        }
    }

}
