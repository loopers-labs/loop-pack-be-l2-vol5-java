package com.loopers.batch.job.ranking;

/** weeklyRanking job 의 업무 규칙 위반 (기간 미종료, revision 순서, snapshot 충돌, 미완성 점수). step 을 실패시킨다. */
public class WeeklyRankingException extends RuntimeException {
    public WeeklyRankingException(String message) {
        super(message);
    }
}
