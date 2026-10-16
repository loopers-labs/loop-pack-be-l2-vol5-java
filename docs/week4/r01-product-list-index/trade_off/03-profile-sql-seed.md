# 전용 프로필에서 SQL seed 실행

[← 전체 선택 현황](total_trade_off.md) · [구현·검증 계획](../plan.md)

## 결정

사용자가 2026-10-16 제안·선택한 방식이다. 데이터를 Java 객체로 반복 저장하거나 CSV로 내보내지 않고,
전용 `benchmark-seed` 프로필의 ApplicationRunner에서 `.sql` 리소스를 실행한다.
실행기 인터페이스는 최초 CommandLineRunner 구현 뒤 사용자의 추가 선택에 따라 ApplicationRunner로 변경했다.

| 책임 | 구성 |
|---|---|
| 실행 조건 | `benchmark-seed` 전용 프로필 + `benchmark.seed.enabled=true` (기본 false) |
| Runner | ProductSeedRunner가 실행·완료 결과 출력 조율 |
| SQL 실행 | JdbcProductSeedLoader가 ResourceDatabasePopulator 사용; Spring JDBC 의존은 Jdbc 클래스로 제한 |
| 리소스 | `seed/product-benchmark.sql`, 현재 모델의 INSERT만 포함 |
| TX | 대상 검사·적재·검증을 한 TX로 실행, 실패 시 rollback·예외 전파, 제한 3,600초 |
| 종료 | 웹 서버 없이 실행, SpringApplication.run 반환 뒤 seed 프로필일 때 context 종료 |
| 대상 | 같은 Docker MySQL의 전용 스키마 `week4_r01_index_benchmark` |

일반 local/test 프로필에서는 실행되지 않는다. 기존 LocalUserFixtureInitializer와도 함께 실행하지 않는다.
기존 데이터 삭제·초기화 기능은 제공하지 않으며, DB 이름이 다르거나 대상 데이터가 있으면 실행을 거절한다.

사용자가 전체 적재·검증 뒤 한 번 커밋하는 방식을 재확인했다. 청크별 커밋은 사용하지 않는다.
상품과 두 좋아요 INSERT SELECT의 결과를 삽입 PK 오름차순으로 정렬한다. 공식·ID·관계는 동일하며,
정렬에 따른 적재 시간의 개선 폭은 재실행 전에는 단정하지 않는다.
InnoDB의 PK 순서 적재 권장은 [MySQL 공식 문서](https://dev.mysql.com/doc/refman/8.0/en/optimizing-innodb-bulk-data-loading.html)를 참고한다.

## 스키마와 인덱스

Hibernate DDL은 Runner보다 먼저 실행되므로 Runner의 DB 검사로 `ddl-auto=create`를 보호할 수는 없다.
전용 프로필의 기본은 `ddl-auto=none`이다. 새 빈 스키마를 확인한 뒤 seed를 끈 상태에서 최초 스키마만 생성한다.
그 후 `ddl-auto=none`으로 seed를 별도 실행한다. 현재 전체 엔티티의 스키마와 기존 상품 인덱스 4개를 사용한다.
추가 예정 인덱스 2개는 변경 전 계획·JMeter 기준선을 확보할 때까지 적용하지 않는다.

## 재현 분포

기준 시각은 `2026-10-01 00:00:00 UTC`다. 상품 순번 `n=0..999999`, `id=n+1`, `b=n%1000+1`, `t=floor(n/1000)`으로 정의한다.

| 항목 | 분포 |
|---|---|
| 브랜드·사용자·지갑 | 각각 1,000개, 사용자 지갑 잔액 0 |
| 삭제 브랜드 | `b >= 991` |
| 삭제 상품 | `b >= 991 OR (t+b)%25=0` |
| 가격 | `1000 + 100*((37*b + 17*floor(t/2))%1000)` |
| 생성·수정 시각 | 기준 시각에서 `floor(t/2)`분 이전 |
| 재고 | `(b+t)%10=0`이면 0, 나머지는 `1+(7*b+13*t)%100` |
| 좋아요 수 | `(13*b+17*t)%1000=0`이면 100, 나머지는 `(7*b+11*t)%6` |
| 실제 관계 | `k=1..likeCount`, 사용자 `1+((31*b+17*t+k-1)%1000)` |
| 측정 브랜드 | ID 7 |

가격·생성 시각의 동점, 좋아요 0개·인기 상품, 재고 0개·삭제 상태를 포함한다. 삭제 브랜드의 소속 상품은 모두 삭제한다.

다음은 공식으로 계산한 예상값이며 실제 DB 검증 전에는 실행 결과로 쓰지 않는다.

| 검증 | 예상값 |
|---|---:|
| 상품 전체 / 활성 / 삭제 | 1,000,000 / 950,400 / 49,600 |
| 전체 가격 합계 | 50,950,000,000 |
| 실제 좋아요 / 좋아요 0개 상품 / 좋아요 100개 상품 | 2,597,980 / 166,338 / 1,000 |
| 브랜드 7 전체 / 활성 / 활성 가격 합계 | 1,000 / 960 / 49,004,000 |
| 좋아요 중복·고아 관계·집계 불일치·삭제 브랜드 활성 상품 | 모두 0 |

2026-10-16 승인한 128MiB 환경의 실제 적재·검증에서 이 예상값을 모두 확인했다.
[실행 결과](../result.md)와 [검증 원문](../baseline/seed-verification.txt)에 보존했다.

## 기준선 경계

동일 DB를 사용하는 조회 앱은 seed 프로필 없이 띄운다. 좋아요 집계를 끄고 COUNT 캐시 TTL을 0s로 고정한다.
3종 실제 HTTP 요청의 목록·COUNT SQL과 바인딩·응답을 먼저 저장한 뒤 통계를 갱신한다.
각 SQL 준비 실행 1회 후 EXPLAIN·EXPLAIN ANALYZE를 수집하며, 단일 실행 시간을 JMeter 지연 분포로 해석하지 않는다.
