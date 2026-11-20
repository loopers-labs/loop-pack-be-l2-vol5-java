plugins {
    `java-library`
}

// 주간 랭킹 테이블 정의(ranking-schema.sql)와 그 초기화 설정(ranking.yml)만 담는다.
// commerce-batch가 쓰고 commerce-api가 읽는 같은 테이블을 한 곳에서 정의한다 (docs/week9/design.md D1).
