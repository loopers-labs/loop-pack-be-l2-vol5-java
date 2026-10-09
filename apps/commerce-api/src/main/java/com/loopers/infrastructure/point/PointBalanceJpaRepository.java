package com.loopers.infrastructure.point;

import com.loopers.domain.point.PointBalanceModel;
import com.loopers.domain.point.PointGrant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PointBalanceJpaRepository extends JpaRepository<PointBalanceModel, Long> {
    @Query("select p.userId from PointBalanceModel p where p.userId > :after order by p.userId")
    List<Long> findUserIdsAfter(@Param("after") Long after, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PointBalanceModel p where p.userId = :userId")
    Optional<PointBalanceModel> findRootForUpdate(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PointBalanceModel p left join fetch p.grants where p.userId = :userId")
    Optional<PointBalanceModel> fetchGrantsForUpdate(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from PointBalanceModel p join p.grants g left join fetch g.usages where p.userId = :userId")
    List<PointGrant> fetchUsagesForUpdate(@Param("userId") Long userId);

    Optional<PointBalanceModel> findByUserId(Long userId);
}
