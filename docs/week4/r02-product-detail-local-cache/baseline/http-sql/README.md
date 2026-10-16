# 실제 HTTP 상세 응답과 SQL

2026-10-16 동일 새 JAR `commerce-api-5003d2c.jar`를 OFF/ON으로 각각 새로 실행했다.
전용 DB의 활성 상품 7을 각 모드에서 두 번 조회하고 독립 계산한 전체 응답과 대조했다.
SQL DEBUG·바인딩 TRACE는 이 수집에서만 활성화했으며 JMeter 측정에서는 끈다.

| 모드·요청 | HTTP | SQL SELECT | DB load 증가 | 전체 응답 |
|---|---:|---:|---:|---|
| OFF 첫 요청 | 200 | 1 | 1 | 기대값 일치 |
| OFF 두 번째 요청 | 200 | 1 | 1 | 기대값 일치 |
| ON 첫 요청(miss) | 200 | 1 | 1 | 기대값 일치 |
| ON 두 번째 요청(hit) | 200 | 0 | 0 | 기대값 일치 |

[summary.json](summary.json)에 데이터·인덱스·설정 전후와 지표 차분을 보존했다.
각 요청의 `*-response.json`은 실제 전체 응답, `*-sql.log`는 해당 요청 구간의 SQL·바인딩이다.
hit는 로그 행이 없어 [on-2-sql.log](on-2-sql.log)를 0바이트로 보존한다.
바인딩은 상품 ID 7, 상품 삭제 false, 브랜드 삭제 false다. [captured-query.sql](captured-query.sql)은 이를 치환한 실제 SQL이다.

SQL 수는 요청 구간의 Hibernate SELECT 로그로 세었고 별도로 원본 DAO 호출 counter와 대조했다.
DB load counter 자체를 SQL 수로 간주하지 않는다. 실제 MySQL 기반 HTTP 회귀 5건에서도 Hibernate
`prepareStatementCount`를 이용해 miss 1회·hit 0회, 400·404, 기존 TX 우회를 검증했다.

JAR SHA-256: `6D30AE13AC5E82A3C3EA38C36AEB394035BDB5B7505B336890D79495D4058BBE`.
운영 코드 출발 커밋은 `5003d2c`이며 이후 도구 문서 커밋은 Java 구현을 바꾸지 않았다.
수집 후 전용 API를 종료했고 기존 API PID 34084의 18080/8081 포트는 유지됐다.

원본 프로세스·Prometheus 로그는 [build 수집 폴더](../../../../../build/benchmark/r02/http-sql-20261016-154249-4472878/)에 있다.
재현은 [capture-http-sql.ps1](../../jmeter/capture-http-sql.ps1)을 사용한다.
