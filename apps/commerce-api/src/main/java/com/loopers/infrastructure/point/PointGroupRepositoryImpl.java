package com.loopers.infrastructure.point;

import com.loopers.domain.common.Money;
import com.loopers.domain.point.PointGroup;
import com.loopers.domain.point.PointGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class PointGroupRepositoryImpl implements PointGroupRepository {

    private final PointGroupJpaRepository pointGroupJpaRepository;

    @Override
    public PointGroup save(PointGroup group) {
        return pointGroupJpaRepository.saveAndFlush(group);
    }

    @Override
    public List<PointGroup> findRemainingByUserId(Long userId) {
        return pointGroupJpaRepository.findRemainingByUserId(userId, Money.ZERO);
    }
}
