package com.loopers.infrastructure.order;

import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderJpaRepository orderJpaRepository;

    @Override
    public Optional<OrderModel> find(Long orderId) {
        return orderJpaRepository.findWithItemsById(orderId);
    }

    @Override
    public OrderModel save(OrderModel order) {
        return orderJpaRepository.save(order);
    }

    @Override
    public PageResult<OrderModel> findPageByUserId(Long userId, PageCommand page, ListSort sort) {
        return toPageResult(orderJpaRepository.findIdsByUserId(userId, pageable(page, sort)), page);
    }

    @Override
    public PageResult<OrderModel> findPage(PageCommand page, ListSort sort) {
        return toPageResult(orderJpaRepository.findIds(pageable(page, sort)), page);
    }

    private Pageable pageable(PageCommand page, ListSort sort) {
        Sort.Direction direction = sort.isDescending() ? Sort.Direction.DESC : Sort.Direction.ASC;
        return PageRequest.of(page.page(), page.size(), Sort.by(direction, "createdAt", "id"));
    }

    /** 페이지로 고른 Order 식별자의 순서를 유지한 채 품목을 함께 복원한다. */
    private PageResult<OrderModel> toPageResult(Page<Long> idPage, PageCommand page) {
        List<Long> ids = idPage.getContent();
        if (ids.isEmpty()) {
            return PageResult.of(List.of(), page, idPage.getTotalElements());
        }

        Map<Long, OrderModel> ordersById = orderJpaRepository.findAllByIdIn(ids).stream()
            .collect(Collectors.toMap(OrderModel::getId, Function.identity()));
        List<OrderModel> ordered = ids.stream().map(ordersById::get).toList();
        return PageResult.of(ordered, page, idPage.getTotalElements());
    }
}
