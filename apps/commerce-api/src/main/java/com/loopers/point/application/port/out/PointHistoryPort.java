package com.loopers.point.application.port.out;

import com.loopers.point.domain.PointHistory;
import java.util.List;

public interface PointHistoryPort {

    PointHistory save(PointHistory history);

    List<PointHistory> saveAll(List<PointHistory> histories);
}
