# 인덱스 추가 전 HTTP·SQL 확인

2026-10-16 SQL 수집용 앱에서 각 GET을 한 번 호출한 기록이다. JMeter 부하 표본과 별도다.
앱은 commerce-api-98da9d2.jar, local 프로필, 전용 DB, ddl-auto=none, 집계 false, COUNT TTL 0s였다.
SQL·바인딩 로그를 켜서 아래 3건을 수집한 뒤 이 앱만 종료하고 SQL 로그 OFF 앱으로 바꿨다.

| 시나리오 | HTTP | 관측 SQL 수 | 결과 |
|---|---|---:|---|
| latest | 200 | 2 | 활성 전체 950,400개, 첫 페이지 20개 |
| price | 200 | 2 | 활성 전체 950,400개, 첫 페이지 20개 |
| brand7 | 200 | 2 | 활성 960개, 첫 페이지 20개 |

세 응답 모두 [독립 계산 기대값](../../jmeter/expected-responses.json)과 전체 필드·정렬·페이지 결과가 일치했다.
목록은 상품·브랜드 JOIN의 전체 Projection을 유지하며, COUNT는 실제 코드처럼 products만 읽는다.
여기서 SQL 수는 코드 추정이 아니라 실제 Hibernate 로그에서 각 요청의 목록 1개·COUNT 1개를 센 값이다.

- [요청·관측 SQL 목록](requests.json)
- [latest 응답](latest-response.json) · [SQL·바인딩](latest-sql.log)
- [price 응답](price-response.json) · [SQL·바인딩](price-sql.log)
- [brand7 응답](brand7-response.json) · [SQL·바인딩](brand7-sql.log)
- [실제 바인딩을 치환한 SQL](captured-queries.sql)

captured-queries.sql은 위 로그의 물음표를 BOOLEAN·INTEGER·BIGINT 값으로 치환해 기록한 파일이다.
생성 과정에서 이 파일의 SELECT나 EXPLAIN·EXPLAIN ANALYZE를 추가 실행하지 않았다.
목록 SQL의 실행 계획·DB 단계별 시간과 사용자의 JMeter 본 측정 결과는 아직 수집하지 않았다.
