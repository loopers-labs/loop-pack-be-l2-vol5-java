package com.loopers.infrastructure.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderJpaRepository extends JpaRepository<Order, Long> {

    /** 상세와 확정은 품목이 필요하므로 함께 읽는다. */
    @Query("select o from Order o left join fetch o.items where o.id = :id and o.userId = :userId")
    Optional<Order> findWithItemsByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Query("select o from Order o left join fetch o.items where o.id = :id")
    Optional<Order> findWithItemsById(@Param("id") Long id);

    @Query(
        value = "select o from Order o where (:userId is null or o.userId = :userId) and (:status is null or o.status = :status) "
            + "order by o.createdAt desc, o.id desc",
        countQuery = "select count(o) from Order o where (:userId is null or o.userId = :userId) and (:status is null or o.status = :status)"
    )
    Page<Order> findPage(@Param("userId") Long userId, @Param("status") OrderStatus status, Pageable pageable);
}
