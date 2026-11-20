package com.loopers.domain.ranking;

import java.util.List;
import java.util.Map;

public interface VisibleRankingProducts {
    Map<Long, String> names(List<Long> productIds);
}
