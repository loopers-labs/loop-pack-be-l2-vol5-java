# ranking-schema

주간 구매 랭킹 테이블 4개(`weekly_ranking_inputs`·`revisions`·`scores`·`publications`)의 **테이블 정의(엔티티)만** 공유한다. 쓰기는 commerce-batch 가 하고, commerce-api 는 Repository 없이 JDBC 로 읽기만 한다 (설계 D14·R5).
