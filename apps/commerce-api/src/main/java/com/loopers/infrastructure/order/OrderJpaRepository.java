package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderJpaRepository extends JpaRepository<OrderModel, Long> {

    /** 주문 품목을 함께 복원한다. */
    @EntityGraph(attributePaths = "items")
    Optional<OrderModel> findWithItemsById(Long id);

    /** Order 단위로 페이지를 나누기 위해 식별자만 먼저 조회한다. */
    @Query("select o.id from OrderModel o where o.userId = :userId")
    Page<Long> findIdsByUserId(@Param("userId") Long userId, Pageable pageable);

    @Query("select o.id from OrderModel o")
    Page<Long> findIds(Pageable pageable);

    @EntityGraph(attributePaths = "items")
    List<OrderModel> findAllByIdIn(Collection<Long> ids);
}
