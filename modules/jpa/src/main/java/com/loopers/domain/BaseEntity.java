package com.loopers.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import java.time.ZonedDateTime;

/**
 * 생성/수정/삭제 정보를 자동으로 관리해준다.
 * 재사용성을 위해 이 외의 컬럼이나 동작은 추가하지 않는다.
 *
 * <p>식별자와 생성/수정 정보는 {@link AuditEntity} 가 맡고, 여기는 <b>논리 삭제만</b> 더한다.
 * 기존 사용처는 그대로 이 클래스를 상속하면 되고(동작은 달라지지 않는다),
 * 논리 삭제가 필요 없는 테이블은 {@code AuditEntity} 를 상속해
 * <b>쓰지 않는 {@code deleted_at} 컬럼과 delete/restore 메서드를 얻지 않는다.</b>
 */
@MappedSuperclass
@Getter
public abstract class BaseEntity extends AuditEntity {

    @Column(name = "deleted_at")
    private ZonedDateTime deletedAt;

    /**
     * delete 연산은 멱등하게 동작할 수 있도록 한다. (삭제된 엔티티를 다시 삭제해도 동일한 결과가 나오도록)
     */
    public void delete() {
        if (this.deletedAt == null) {
            this.deletedAt = ZonedDateTime.now();
        }
    }

    /**
     * restore 연산은 멱등하게 동작할 수 있도록 한다. (삭제되지 않은 엔티티를 복원해도 동일한 결과가 나오도록)
     */
    public void restore() {
        if (this.deletedAt != null) {
            this.deletedAt = null;
        }
    }
}
