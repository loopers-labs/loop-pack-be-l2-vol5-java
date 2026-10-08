package com.loopers.domain.like;

public interface ProductLikeRepository {
    boolean exists(long userId, long productId);

    /** 신규 관계를 저장한다. 중복 관계는 저장 오류로 거절한다. */
    void save(ProductLike like);

    /** 같은 관계의 동시 등록도 정상 완료하며 기존 관계는 변경하지 않는다. */
    void registerIfAbsent(ProductLike like);

    /** 관계를 제거한다. 관계가 없어도 정상 완료한다. */
    void delete(long userId, long productId);
}
