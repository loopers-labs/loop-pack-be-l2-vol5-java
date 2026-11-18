package com.loopers.batch.job.ranking;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * weeklyRanking job 설정. job parameter 가 아니다 (같은 작업의 식별에 들어가지 않는다).
 *
 * @param chunkSize 점수 계산 step 의 chunk 크기 (상품 수). 처리량을 측정한 값이 아니다
 */
@ConfigurationProperties("ranking.weekly")
public record WeeklyRankingProperties(@DefaultValue("100") int chunkSize) {
    public WeeklyRankingProperties {
        if (chunkSize < 1) {
            throw new IllegalArgumentException("ranking.weekly.chunk-size 는 1 이상이어야 합니다. [chunkSize = " + chunkSize + "]");
        }
    }
}
