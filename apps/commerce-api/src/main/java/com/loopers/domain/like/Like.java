package com.loopers.domain.like;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.ZonedDateTime;

/**
 * 사용자와 상품의 좋아요 관계. {@code (productId, userId)}가 기본키라 같은 관계는 하나만 존재한다.
 *
 * <p>취소하면 행을 지우는 hard delete 이므로 {@code BaseEntity}(수정·삭제 시각)를 상속하지 않고,
 * 좋아요한 시각만 가진다. 사용자와 상품은 id 로만 참조한다.
 */
@Getter
@Entity
// 기본키는 LikeId 의 선언 순서대로 (product_id, user_id)라 상품별 좋아요 수 집계(product_id 조건)는 기본키의 왼쪽 접두로 받는다.
// 그래서 product_id 보조 인덱스는 두지 않는다
// 반대로 사용자 조건 조회는 기본키를 쓸 수 없어, 내 좋아요 목록(user_id 조건)용 보조 인덱스를 둔다.
// 사용자당 좋아요가 적어(측정 46~62건) 정렬은 인덱스에 담지 않고 user_id 한 컬럼만 둔다.
@Table(name = "likes", indexes = @Index(name = "idx_likes_user_id", columnList = "user_id"))
public class Like {

    @EmbeddedId
    private LikeId id;

    @Column(name = "liked_at", nullable = false)
    private ZonedDateTime likedAt;

    // JPA 가 사용하는 기본 생성자. 새 좋아요는 create() 로 만든다.
    protected Like() {}

    public static Like create(Long userId, Long productId) {
        Like like = new Like();
        like.id = new LikeId(productId, userId);
        like.likedAt = ZonedDateTime.now();
        return like;
    }
}
