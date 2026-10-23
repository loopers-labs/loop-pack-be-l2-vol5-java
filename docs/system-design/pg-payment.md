# PG 결제 기반

이 브랜치는 결제 요청, 외부 HTTP adapter와 결과 적용의 기반이다. 조회 CircuitBreaker는 이 기준 위의 별도 변경으로 검토한다.

## 트랜잭션과 상태

- TX A는 소유권과 주문, 상품을 검사하고 재고 확보, REQUESTED 시도와 PAYMENT_PENDING 주문을 함께 저장한다.
- 새 시도를 commit한 실행만 TX 밖에서 PG 생성 POST를 호출한다. PG 응답을 기다리는 동안 DB 트랜잭션과 재고 잠금을 유지하지 않는다.
- callback과 reconcile, 주기 조회는 기존 거래를 조회하고 공통 TX B에서 결과를 반영한다. 생성 POST는 자동 재전송하지 않는다.
- 성공은 추가 재고 차감 없이 확정하고, 확정 실패는 저장 품목 수량을 한 번 반환한다. 처리 중과 미확인은 예약을 유지한다.
- 외부 성공 뒤 내부 저장이 실패하면 TX B를 rollback하고 같은 거래를 재조회한다. 조건부 종결과 상태 재조회로 중복 효과와 늦은 응답의 퇴행을 방지한다.
- 이전 POINT 주문의 결제 사실과 품목, 포인트 충전과 조회는 보존한다.

## 외부 계약과 멱등성

고객의 paymentAttemptKey, 저장한 providerOrderId와 PG의 transactionKey를 구분한다. 주문당 한 시도와 세 유일 제약, DRAFT 조건부 전이로 중복을 막는다. 카드 입력은 저장하거나 재요청 비교에 사용하지 않는다.

PG의 생성 POST와 두 조회 GET은 고정 로컬 주소와 합성 계정 135135를 사용한다. 생성 수락 PENDING을 성공으로 보지 않으며, 미확인 생성 요청은 재전송하지 않는다. 422 효과 전 거절은 정확한 code, boolean false와 요청 식별자·금액을 모두 확인한 경우에만 적용한다.

## 검증과 외부 fixture

테스트 PG 앱과 Docker, DB 초기화·이관 파일은 이 브랜치에서 배포하지 않는다. 순수 단위 테스트는 별도 fixture 없이 실행한다.

```bash
./gradlew :apps:commerce-api:test --tests '*PaymentAttemptTest'
```

통합 테스트는 별도로 보관한 로컬 fixture 루트를 지정한다. fixture에는 아래 네 파일이 필요하다. JAR는 해당 테스트 PG에서 미리 빌드한다. Docker 실행 환경과 Java 21도 필요하다.

- `apps/test-pg/Dockerfile`
- `apps/test-pg/build/libs/test-pg.jar`
- `docker/mysql/pg-payment-safety-migration.sql`
- `docker/mysql/pg-payment-conflict-migration.sql`

```bash
./gradlew :apps:commerce-api:check -PpgFixtureRoot=/path/to/pg-fixture
```

pgFixtureRoot의 기본값은 저장소 루트다. fixture가 필요한 테스트에서 파일이 없으면 실패하며 테스트를 건너뛰지 않는다. Gradle이 제외한 PG 모듈을 자동 빌드하는 의존성은 두지 않는다. 애플리케이션의 local DB에도 PG 주문 상태, 결제 시도와 유일 제약을 갖춘 스키마가 별도로 필요하다.

발송 직전 종료와 장기 미확인, 충돌 해제와 수동 종결은 남은 한계다. 시간 경과나 조회 0건만으로 결제 실패와 예약 반환을 확정하지 않는다.

## 로컬 검증 기록

외부 fixture를 제공한 이 기준 브랜치의 서비스 check에서 테스트 648개와 Checkstyle, ArchUnit을 통과했다. 실패, 오류와 건너뜀은 0이다. 위 순수 단위 테스트 명령도 외부 fixture 없이 통과했다.
