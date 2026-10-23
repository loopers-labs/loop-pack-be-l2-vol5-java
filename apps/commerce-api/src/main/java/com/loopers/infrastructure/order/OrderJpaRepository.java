package com.loopers.infrastructure.order;

import com.loopers.domain.order.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderJpaRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id and o.userId = :userId")
    Optional<Order> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    // 정렬은 쿼리에 고정한다. `:userId is null or ...` 의 userId 는 목록 API 의 선택 필터 인자다.
    // null 이면 모든 주문을, 값이 있으면 그 구매자의 주문만 조회한다 (주문의 user_id 는 null 일 수 없다).
    @Query(
        value = "select o from Order o where (:userId is null or o.userId = :userId) order by o.id desc",
        countQuery = "select count(o) from Order o where (:userId is null or o.userId = :userId)"
    )
    Page<Order> findAllByUser(@Param("userId") Long userId, Pageable pageable);
}
