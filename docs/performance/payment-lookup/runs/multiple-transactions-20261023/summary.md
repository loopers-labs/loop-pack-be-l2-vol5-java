# 복수 거래 충돌 보존 검증

주문 단위 PG 조회에서 거래가 2건 이상이면 `MULTIPLE_TRANSACTIONS`를 기존 충돌 기록 경계에 전달한다. 처음 사유와 감지 시각만 저장하고 시도 상태, 거래 키, 주문, 재고는 유지한다. 거래를 임의 선택하거나 create를 다시 보내지 않는다.

## 결정적 재현

테스트 준비 단계에서 실제 adapter로 PG 거래를 2건 또는 3건 만든다. 이는 비정상 외부 상태를 준비하기 위한 호출이며 Commerce의 confirm 재발송이 아니다. 모든 거래를 SUCCESS로 처리한 뒤 UNKNOWN 시도에서 주문 단위 조회를 두 번 수행한다.

- 관찰은 두 번 모두 CONFLICT이며 최초 `MULTIPLE_TRANSACTIONS`와 감지 시각을 유지한다.
- MySQL을 flush/clear 후 재조회하면 시도 UNKNOWN, 거래 키 NULL, 주문 PAYMENT_PENDING이다.
- 초기 재고 10개에서 확보한 2개를 유지해 가용 수량은 8개이며 포인트 100,000도 유지한다.
- 거래 수와 create 횟수는 준비한 2건 또는 3건 그대로이며 조회는 2회다. 조회 이후 추가 create는 0회다.
- 이미 거래 키가 있는 PENDING과 종결된 SUCCESS에 늦은 복수 거래 관찰을 적용해도 상태, 거래 키와 재고는 유지한다.

DB ENUM은 새 DB용 SQL과 기존 PG DB용 [충돌 사유 확장 SQL](../../../../../docker/mysql/pg-payment-conflict-migration.sql)에 반영했다. 실제 MySQL의 별도 테스트 테이블에 전환 SQL을 실행해 기존 사유, 감지 시각, 상태, 거래 키와 NULL 보존, 새 사유 저장을 검증했다. 사용자의 기존 DB에는 실행하지 않았다.

## 검사 결과

- Red: 추가한 결과 반영 4개 케이스 모두 `CONFLICT`를 기대했지만 `UNCHANGED`를 반환해 실패했다.
- Green: 결과 반영, 저장소, 도메인, SQL 전환, ArchUnit 테스트 104개와 Checkstyle 통과.
- 전체 검사: Commerce 699개, test-pg 112개, 실패·오류·건너뜀 모두 0개. 두 모듈의 Checkstyle과 ArchUnit 2개 통과. 3분 52초, 30개 작업 모두 실행.

실제 명령, 재현 명령, 집계와 소스 해시(`verification.json`), [결과 파일 분리 실행](../../README.md#결과-파일을-분리한-전체-검사)을 함께 확인한다. 검사에 사용한 [init script](../../isolated-build.init.gradle)도 저장소에 보관했다. 복수 거래 해소와 수동 종결 절차는 추가하지 않았으며 정상 결제 5분 부하도 다시 실행하지 않았다.
