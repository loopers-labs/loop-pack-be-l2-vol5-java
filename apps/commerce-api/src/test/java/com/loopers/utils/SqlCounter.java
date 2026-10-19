package com.loopers.utils;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

/**
 * Hibernate 가 준비한 PreparedStatement 수를 센다 (JPQL·QueryDSL·em.find·네이티브 쿼리 포함, 드라이버의 SET/COMMIT 제외).
 * 세는 구간 직전에 reset(), 직후에 count(). 통계는 SessionFactory 전역이라 테스트가 끝나면 disable() 로 끈다.
 */
public class SqlCounter {

    private final Statistics statistics;

    public SqlCounter(EntityManagerFactory entityManagerFactory) {
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        this.statistics.setStatisticsEnabled(true);
    }

    public void reset() {
        statistics.clear();
    }

    public long count() {
        return statistics.getPrepareStatementCount();
    }

    public void disable() {
        statistics.setStatisticsEnabled(false);
    }
}
