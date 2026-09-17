package com.loopers.domain.point;

import java.util.List;

public interface PointHistoryRepository {

    PointHistory save(PointHistory history);

    List<PointHistory> saveAll(List<PointHistory> histories);
}
