# 7주차 설계 · Outbox 와 재처리로 중단 구간 닫기

- 과제: `docs/week7/7week_docs.pdf` (실습, 9쪽) · 학습: `docs/week7/7week_reference.pdf`
- 이전 설계: `docs/week6/order-confirmed-event-design.md` (W6 종료 지점 = 로컬 태그 `w6-before-outbox`, 커밋 17d7c44)
- 페이지 표기(📄 p.N)는 `7week_docs.pdf` 기준

---

## 0. 전체 흐름

```
[W6 · 태그 w6-before-outbox]                 [W7 · 이번 주 바꾸는 경계]
 PG SUCCESS 검증 (TX 밖)                      PG SUCCESS 검증 (TX 밖)
   → applyFinal (TX B)                          → applyFinal (TX B)
       · 주문 CONFIRMED · 예약 소비                  · 주문 CONFIRMED · 예약 소비
       · 이벤트 payload 구성                         · 이벤트 payload 구성
     ← commit                                       · 같은 TX 에 OrderConfirmed Outbox PENDING insert
   → Facade 가 commit 뒤 Kafka 직접 send          ← commit
       (commit 뒤 ~ send 전 중단 = 누락)          → 단일 relay 가 PENDING 을 읽어 send → broker 확인 뒤 PUBLISHED
             ▼                                              ▼
 [commerce-streamer] 기존 consumer (W6 그대로: 장부 + 구매 활동, 중복은 no-op, 영구 오류는 DLT)
```

> 📄 p.1 "변경할 부분은 확정 후 직접 발행을 Outbox 기록과 단일 relay로 교체하는 경계입니다."
> 📄 p.1 "W6의 after-commit 직접 send를 제거하거나 이 경로에서 호출되지 않게 교체합니다. 직접 send와 relay를 의도치 않게 병행하지 않습니다."

| 유지할 것 (📄 p.1) | 이번에 추가·교체할 것 (📄 p.1) |
|---|---|
| PG 결과 검증·예약 소비·CONFIRMED 같은 확정 TX | 확정 TX 의 Outbox insert |
| stable eventId·orderId·confirmedAt·items | 변경되지 않는 key·payload 영속 저장 |
| Kafka topic·소비 group·구매 활동 | 단일 polling relay·전송 상태 |
| 소비 중복 방지·DB TX·ack | 발행 중단·재시작·기존 누락 복구 검증 |

작업 순서: **1단계 = 과제 5절 준비(5-1~5-3)** 를 W6 경로로 먼저 만들어 누락 상태를 재현한다 (이 문서 1절). **2단계 = 과제 1~2절** Outbox 모델·같은 TX·직접 발행 제거 (이 문서 2절). **3단계 = 과제 3~4절** 단일 relay·재시도 (이 문서 3절). 그 뒤 복구(5-4~5-6)·중단 실험(6절)·E2E(7절)를 같은 테스트 메서드에 이어 붙인다 (이 문서 4절, 미정). 실험 관찰값은 5절에 모은다.

---

## 1. 5절 준비(5-1 ~ 5-3) 결정

### D1. W6 종료 지점 → 로컬 annotated 태그 `w6-before-outbox` + 브랜치 `applejeong/volume-7`
- W6 마지막 커밋 17d7c44 에 태그를 붙이고(메시지 "W6 after-commit 직접 발행 상태 (W7 재현용)"), 같은 커밋에서 `applejeong/volume-7` 을 분기했다. 태그·브랜치는 push 하지 않았다.
- 과제가 준비 단계의 출발점으로 "W6 revision" 을 허용하므로, 어느 커밋이 W6 인지 이름으로 고정해 둔다.
  > 📄 p.4 5-1 "준비 단계에서는 보존한 W6 테스트 구성 또는 W6 revision 실행물로 실제 주문 확정·after-commit 직접 발행 경로를 실행합니다."
- 태그는 로컬에만 있으므로 PR 에서는 커밋 해시 17d7c44 로 가리킨다 (원격 `applejeong/volume-6` 에 있음).
- 태그 시점 미커밋 파일(`.gitignore`, `CLAUDE.md`, `docs/week6/*.md`, `docs/week7/`)은 문서·설정뿐이라 코드 상태 표시에는 영향이 없고, 커밋하지 않고 그대로 두었다.

### D2. W6 직접 발행 경로 보존 → 테스트 전용 컴포넌트 `W6DirectPublishPath` (B안)
- W7 이 제품 코드의 직접 발행을 Outbox 로 바꾼 뒤에도 "확정 TX 는 commit 됐지만 Outbox 도 발행도 없는" 상태를 새 격리 DB 에 만들 수 있어야 한다. 그래서 W6 경로를 `apps/commerce-api/src/crossAppTest/java/com/loopers/crossapp/W6DirectPublishPath.java` 에 보존했다.
  > 📄 p.4 5절 "W6의 임시 DB가 종료돼도 시작할 수 있어야 합니다. 이번 검증은 역사적 원본 행의 복원이 아니라, W6 누락 상태 재현 → 같은 실행 안에서 복구입니다."
- 내용은 태그 시점 `PaymentResultService.applyFinal`(40~76행)의 SUCCESS 분기(시도 종결 → 주문 CONFIRMED → 품목으로 `OrderConfirmedEvent` 생성 → 예약 CONSUMED, Outbox 없음) + `PaymentFacade` 의 TX 밖 PG 조회·네 항목 대조(54~72, 192~215행) + commit 뒤 발행(247~258행)이다. 출처는 클래스 주석에 적었다.
- 원본과 다른 점 두 가지: `@Transactional` 대신 `TransactionTemplate` 로 같은 REQUIRED TX 를 연다(한 클래스 안에 TX 경계를 두기 위해). FAILED·PENDING 분기와 충돌 기록은 복제하지 않고, 검증된 SUCCESS 1건이 아니면 예외로 드러낸다.
- SUCCESS 를 DB 에 직접 insert 하는 지름길은 쓰지 않는다. 실제 리포지터리·실제 PG 조회(가짜 PG 는 응답만 흉내)·실제 발행기를 거친다.
  > 📄 p.4 5-4 "준비 상태를 만들려고 SUCCESS를 직접 삽입하거나 W7 Outbox 행을 삭제하지 않으며, 제품의 운영용 우회 스위치도 만들지 않습니다."
- 모든 것이 테스트 소스에만 있으므로 제품 코드에는 스위치가 없다. 제품 설정 플래그로 Outbox 를 생략하는 변형은 5-4 위반이라 택하지 않는다.
- **A안(태그 시점 코드를 그대로 실행) 기각 이유:** W6 테스트의 MySQL·Kafka 는 Testcontainers 로 그 JVM 안에서만 살고 PG 거래·호출 횟수는 `FakePaymentGateway` 메모리에만 있어, JVM 이 끝나면 W7 복구가 "같은 DB·같은 실행" 에 붙을 수 없다. W6 테스트는 `@AfterEach` 에서 전체 테이블을 비워 5-3 도 위반한다.

### D3. send 직전 멈춤 → 기존 가짜 발행기의 hook 예외 재사용
- `FakeOrderConfirmedPublisher.beforeSend` 가 예외를 던져 실제 Kafka 발행기로의 위임을 막는다. 확정 TX 는 이미 commit 된 뒤이므로 주문·예약은 남고 broker record 는 없다.
  > 📄 p.5 6절 "중단 hook은 그 단계가 완료된 뒤 다음 단계를 못 가게 둡니다."
- 이 중단은 통제된 hook 이며 프로세스 종료가 아니다. 문서·주석·테스트 이름에서 "OS 강제 종료" 라고 쓰지 않는다.
  > 📄 p.5 6절 "예외를 던진 테스트를 OS 강제 종료 시험으로 표현하지 않습니다."
- 대안(hook 이 조용히 send 를 건너뛰기)은 로그가 없어 관찰 근거가 줄어 택하지 않았다. W6 과 같은 방식이라 비교도 쉽다.

### D4. consumer 로드 → commerce-api 의 별도 소스셋 `crossAppTest` + streamer `-plain.jar` + 테스트 전용 부트스트랩
- 과제는 테스트 의존이나 별도 context 로 실제 consumer 를 올리되 제품 runtime 의존은 금지한다.
  > 📄 p.6 "테스트 의존성 또는 별도 application context로 실제 consumer를 로드할 수 있습니다. 제품 runtime에 commerce-api→commerce-streamer 의존성을 새로 만들지 않습니다."
- 두 앱이 모두 `com.loopers` 패키지라 `src/test` 에 streamer 를 넣으면 기존 `@SpringBootTest` 36개가 "`@SpringBootConfiguration` 여러 개" 로 깨지고, component scan 이 streamer 빈(`purchase-activity.*` 값 필요, `Clock` 빈 중복)까지 올린다. 그래서 **별도 소스셋** `crossAppTest` 를 만들고 streamer 의존(`crossAppTestImplementation`)은 거기에만 붙였다. 기존 `src/test` classpath 와 제품 `implementation` 의존은 불변이다.
  > 📄 p.7 "실제로 사용하는 listener factory·parser·handler를 명시적으로 연결하고 두 애플리케이션의 전체 component scan 충돌을 피합니다."
- 루트 build 가 apps 하위 앱의 일반 jar 를 끄고 있어 프로젝트 의존이 빈 껍데기가 된다. streamer build 에만 `tasks.jar { enabled = true }` 를 켰다(`-plain.jar`, bootJar 영향 없음). 다른 앱 정책은 그대로다.
- 부트스트랩 `CrossAppTestConfig` 는 두 Application 클래스와 streamer 전용 패키지를 scan 에서 빼고, streamer 에서 실제로 쓰는 6개(`OrderActivityKafkaConfig`, `OrderConfirmedListener`, `OrderConfirmedEventParser`, `PurchaseActivityService`, `PurchaseActivityRepositoryImpl`, `KafkaDeadLetterPublisher`)만 `@Import` 한다. `@TestConfiguration` 이 아니라 `@Configuration` 인 이유: `@SpringBootTest(classes=…)` 에 `@TestConfiguration` 만 넘기면 Boot 가 `@SpringBootConfiguration` 을 다시 찾아 두 Application 을 모두 발견한다.
- streamer 의 `application.yml` 도 classpath 에 올라오므로 자기 main·test 출력물을 streamer jar 앞에 두어 commerce-api 의 yml 이 읽히게 했고, `purchase-activity.*` 값은 테스트의 `@SpringBootTest(properties=…)` 로 넣는다. `@EnableScheduling` 은 올리지 않는다(스케줄러 조회가 제품 경로로 먼저 확정하면 W6 재현이 아니게 된다).
- `crossAppTest` 태스크는 `check` 에 포함되며 Checkstyle(`checkstyleCrossAppTest`)도 자동 적용된다. 실행: `./gradlew :apps:commerce-api:crossAppTest`.
- 기각한 대안: 기존 36개 테스트에 `classes=` 지정(제품 Application 수정 필요), 새 Gradle 모듈(변경량 큼, 과제 예시 명령 경로와 다름), streamer 를 별도 프로세스로 실행(느리고 단언이 어려움).

### D5. streamer 엔티티 테이블 → `JpaConfig` 의 `@EntityScan("com.loopers")` 자동 생성
- `modules/jpa` 의 `JpaConfig` 가 `@EntityScan("com.loopers")`·`@EnableJpaRepositories("com.loopers.infrastructure")` 로 선언돼 있고 test 프로파일은 `ddl-auto: create` 다. streamer 클래스가 classpath 에 있으면 `processed_order_confirmation`·`product_purchase_activity` 와 JPA 리포지터리가 별도 코드 없이 같은 MySQL 에 생긴다.
- 테스트 끝의 `DatabaseCleanUp` 도 메타모델 기준이라 이 두 테이블까지 비운다.
- 명시 방식(테스트 전용 JPA 설정에 패키지 나열)은 결과가 같고 코드만 늘어 택하지 않았다.

### D6. 기록 → `build/w7-recovery/observation.json` + 테스트 단언, W6 기록과 분리
- 이번 재현 실행의 orderId·eventId·confirmedAt·items·PG 거래 ID·C·T 를 `apps/commerce-api/build/w7-recovery/observation.json` 에 남기고, 같은 값을 테스트 안에서도 단언한다. 기록은 전부 새 DB 조회값이다(확정 전에 들고 있던 엔티티 객체를 쓰지 않는다).
  > 📄 p.4 5-3 "두 주문의 실제 orderId, eventId, confirmedAt, items와 PG 거래 식별자를 기록합니다. W6 기록과는 별도인 이번 재현 실행의 사건입니다."
- W6 기록(`build/delivery-gap/`, W6 설계 문서 8절)과 섞지 않고 W6 설계 문서는 수정하지 않는다.
- `build/` 는 `.gitignore` 대상이라 저장소에 들어가지 않는다. 제출용 수치는 이 문서 5절에 옮긴다.

### D7. 고정 clock → `2026-11-07T01:00:00.123456789Z` (밀리초 절단 `…00.123Z`)
- W6 값(`2026-09-07…`)과 다르게 두어 "이번 실행의 사건" 임이 값만 봐도 구분된다. 두 앱 모두의 `Clock` 빈을 scan 에서 빼고 테스트가 `@MockitoBean` 으로 고정값을 넣는다.
- 이벤트 confirmedAt·주문 DB 값·streamer 저장값을 정확히 비교할 수 있다 (W6 D2 와 같은 이유).

### D8. 정리 시점 → 테스트 맨 끝 한 번만, `@AfterEach` 없음
- 준비와 복구 사이에 DB·broker·PG 를 초기화하지 않는다. 복구 5-4~5-6 은 같은 테스트 메서드의 TODO 자리에 이어 붙이고, 정리(`DatabaseCleanUp.truncateAllTables`)는 그 뒤에서만 호출한다.
  > 📄 p.4 5-3 "이후 복구가 끝날 때까지 DB·broker·PG 상태를 초기화하거나 @AfterEach 정리를 사이에 실행하지 않습니다."
  > 📄 p.5 "모든 확인 뒤에만 자신이 만든 자원을 정리합니다."
- 단언이 중간에 실패하면 정리가 실행되지 않지만, Testcontainers 가 JVM 종료 시 컨테이너를 내리므로 남는 자원은 없다.

### D9. 합성 주문 입력 → 기존 `OrderFacade` 생성·결제 시작, PG 는 `FakePaymentGateway`, 상품 A 1000원×2·B 2000원×1
- 주문 생성·결제 시작은 W6 누락 실험과 같은 기존 경로(`createOrder` → `confirmOrder`)를 쓰고, 확정만 D2 의 보존 경로로 한다. PG 응답은 인메모리 가짜(`FakePaymentGateway`)이며 거래 ID 는 `FAKE-TX-%06d` 형식이다.
  > 📄 p.4 5-2 "기존 주문 API와 로컬 PG로 두 합성 주문을 확정합니다. 첫 주문은 검증한 PG SUCCESS의 내부 확정 commit 뒤 send 직전에서 멈추고, 둘째는 실제 발행·소비를 끝냅니다."
- 두 주문 모두 품목은 상품 A × 2, 상품 B × 1 (W6 과 같은 고정 입력). 주문 B 는 격리 topic 에 발행되고 같은 context 의 실제 listener 가 ack 할 때까지(committed offset 전진) 30초 deadline 안에서 기다린다.
- PG 호출 수 기준값은 `createCount()` = C, `transactions().size()` = T 로 읽어 기록한다. 최소 사례라 C=2, T=2 를 단언한다.
  > 📄 p.5 "두 주문을 각 한 번 시작한 최소 사례라면 C=2, T=2입니다. 복구 시작 직전 기준값을 기록하고 이후 증가가 0인지 확인합니다."
- "Outbox 는 아직 없음" 확인은 Outbox 테이블이 생긴 뒤 채운다(테스트 안 TODO).

---

## 2. 과제 1~2절 결정 · 바꿀 트랜잭션 표시와 Outbox 모델·로컬 원자성

이 절의 범위는 과제 1절(바꿀 트랜잭션)·2절(Outbox 모델·같은 TX)과 그에 따른 직접 발행 제거까지다. relay(과제 3절)·재시도·운영 재개(과제 4절)는 이 문서 3절, 복구(과제 5-4~5-6)는 이 문서 4절에서 따로 정한다.

### D10. 바꿀 트랜잭션 → `PaymentResultService.applyFinal` 의 `confirmedRows == 1` 블록
- 세 입구(`applyCallback`·`reconcile`·`runScheduledLookups`)가 모두 `PaymentFacade.apply` → `applyFinal` 로 모이므로 여기 한 곳에 Outbox insert 를 두면 세 경로가 같은 동작을 한다.
  > 📄 p.1 "기존 PG 결과 적용 경계에서 최초 SUCCESS의 내부 확정을 찾습니다. 새 주문을 insert하는 코드가 아니라 기존 미완료 주문을 확정하는 코드입니다."
  > 📄 p.1 "callback·reconcile은 계속 같은 결과 적용 경계를 사용합니다."
- 정확한 자리는 `finishIfPaymentPending` 이 1행을 바꾼 뒤 품목을 읽어 `OrderConfirmedEvent.of(...)` 를 만든 직후다. 예약 소비 루프와 같은 메서드 안이라 같은 `@Transactional`(REQUIRED) 로 commit·rollback 된다. 데이터소스는 `modules/jpa` 의 `mySqlMainDataSource` 하나, 트랜잭션 관리자도 하나다.
  > 📄 p.2 "Outbox insert와 주문 확정·예약 소비·PG 성공 사실 저장은 같은 데이터 소스의 같은 로컬 TX에 둡니다. 별도 REQUIRES_NEW 나 after-commit insert로 나누지 않습니다."
- PENDING(`recordPending`)·UNKNOWN(`recordUnknown`)·충돌(`recordConflict`)은 `applyFinal` 을 거치지 않고, FAILED 는 실패 분기라 이 블록에 들어오지 않는다. 중복 callback 은 `settleIfOpen` 0행으로 먼저 반환된다. 현재 구조 그대로 아래 조건을 만족한다.
  > 📄 p.2 "PENDING·UNKNOWN·확정 FAILED는 OrderConfirmed Outbox를 만들지 않습니다. 같은 거래의 중복 callback·reconcile은 Outbox도 하나만 남깁니다."

### D11. Outbox 위치·이름 → `domain/order/OrderConfirmedOutbox` + `OrderConfirmedOutboxRepository`, 구현 `infrastructure/order`, 테이블 `order_confirmed_outbox`
- `PaymentResultService`(domain) 가 저장을 호출하므로 ArchUnit 규칙("domain 은 infrastructure 에 의존하지 않는다")상 엔티티와 리포지터리 인터페이스는 domain, JPA 구현은 infrastructure 에 둔다. 기존 `PaymentAttempt` / `PaymentAttemptRepository` / `PaymentAttemptRepositoryImpl`·`PaymentAttemptJpaRepository` 관례와 같다.
  > 📄 p.2 "클래스·테이블·패키지 이름은 기존 설계와 ArchUnit 규칙에 맞게 정합니다."
- payload 계약인 `OrderConfirmedEvent` 가 이미 `domain/order` 에 있어 같은 자리에 둔다. 이벤트가 하나뿐이라 범용 `outbox` 패키지·`eventType` 분기 테이블은 만들지 않는다 (W6 D9 와 같은 "필요 이상 묶지 않기").
- 엔티티는 `BaseEntity` 를 상속해 id·created_at·updated_at 을 그대로 쓴다. 컬럼은 과제 p.2 데이터 규칙을 그대로 옮긴다.

| 컬럼 | 값 | 비고 |
|---|---|---|
| event_id | `order-confirmed:v1:{orderId}` | UNIQUE (D13) |
| order_id | 주문 ID | UNIQUE (D13) |
| event_type / schema_version | `OrderConfirmed` / `1` | W6 계약 그대로 |
| message_key | orderId 문자열 | Kafka key |
| payload | JSON 문자열 | TEXT, 생성 뒤 변경 금지 (D12) |
| status | PENDING / PUBLISHED | VARCHAR (D14) |
| created_at | BaseEntity | 기록 시각 (D15) |
| published_at | null → broker 확인 뒤 | 발행 확인 저장 시각 (D15) |
| attempt_count / next_attempt_at / last_error | 0 / 저장 시각(Clock) / null | 갱신은 relay 단계에서 (D15) |

  > 📄 p.2 "eventId W6의 동일 확정 사건 ID, DB 유일 제약 / orderId·eventType·schemaVersion 원본 주문과 계약 연결 / messageKey·payload W6와 동일, 생성 뒤 변경 금지 / status 최초 PENDING, broker 확인 후 PUBLISHED / createdAt·publishedAt 기록 시각과 발행 확인 저장 시각 구분 / attemptCount·nextAttemptAt·lastError 재시도·지연·실패 관찰"

### D12. payload → JSON 문자열을 TEXT 계열(utf8mb4) 컬럼에, 확정 TX 안에서 한 번만 직렬화
- `KafkaOrderConfirmedPublisher` 가 쓰는 것과 같은 `ObjectMapper` 로 `OrderConfirmedEvent` 를 직렬화해 문자열로 저장한다. relay 는 저장된 문자열을 UTF-8 bytes 로 바꿔 그대로 보내므로 W6 D6 의 "우리가 만든 JSON bytes 그대로" 계약과 일치하고, 현재 상품을 다시 읽어 payload 를 만들지 않는다.
  > 📄 p.2 "messageKey·payload W6와 동일, 생성 뒤 변경 금지"
  > 📄 p.3 "relay가 현재 상품 정보를 재조회해 payload를 다시 만들지 않습니다."
- MySQL `JSON` 타입은 저장 시 정규화돼 공백·키 순서가 바뀔 수 있어 "생성 뒤 변경 금지" 와 충돌할 수 있다. 그래서 `TEXT`(`columnDefinition = "TEXT"`, 서버 기본 문자셋 utf8mb4)로 둔다. 품목 몇 개짜리 JSON 이라 64KB 상한은 충분하다.
- bytes(`VARBINARY`/`BLOB`) 저장은 SQL 로 바로 읽기 어려워 과제 4절의 "SQL 로 확인" 과 덜 맞아 택하지 않았다.

### D13. eventId → W6 계산식 그대로, `event_id`·`order_id` 각각 UNIQUE
- eventId 는 `OrderConfirmedEvent.eventIdOf(orderId)` = `order-confirmed:v1:{orderId}` 를 그대로 쓴다. W6 소비자 장부(`uk_poc_event_id`)·녹화 record 와 같은 값이고, 복구 5-5 에서 저장된 주문만으로 같은 eventId 를 다시 만들 수 있다. UUID 를 새로 발급하면 이것이 불가능하다.
  > 📄 p.2 "eventId W6의 동일 확정 사건 ID, DB 유일 제약"
- 유일성은 `finishIfPaymentPending` 의 `WHERE status = PAYMENT_PENDING` 가드가 먼저 보장하고, UNIQUE 는 최후 방어다 (W6 D11 과 같은 태도). `order_id` UNIQUE 는 소비자 장부(`uk_poc_order_id`)와 대칭이 되도록 함께 둔다.

### D14. status → `PENDING`, `PUBLISHED`, `BLOCKED` 세 값, `@Enumerated(STRING)` + VARCHAR
- 과제 2절 구현(1~2절 단계)에서는 `PENDING`·`PUBLISHED` 두 값으로 시작했고, relay·재시도 설계(D29)에서 영구 오류 분리용 `BLOCKED` 를 추가했다. 문자열 컬럼이라 값 추가에 스키마 변경이 없다.
  > 📄 p.2 "status 최초 PENDING, broker 확인 후 PUBLISHED"
  > 📄 p.4 "별도 BLOCKED 상태로 분리했다면 미해결 상태로 목록에 남기고 복구 후 같은 행을 재개하는 경로를 둡니다."
- `BLOCKED` 는 완료가 아니라 사람이 다시 판단해야 할 미해결 작업이다. 전이 규칙과 재개 경로는 D29.

### D15. 시각 → `createdAt` 은 `BaseEntity`(`@PrePersist`), `publishedAt`·`nextAttemptAt` 은 relay 가 `Clock` 빈으로 채움
- `BaseEntity` 는 "이 외의 컬럼이나 동작은 추가하지 않는다" 고 못 박고 있어 건드리지 않는다. created_at 은 시스템 시계라 고정 Clock 과 어긋날 수 있지만, 사건 시각 자체는 payload 안 confirmedAt(Clock 값)에 있으므로 테스트 단언에 지장이 없다.
- "기록 시각" 과 "발행 확인 저장 시각" 은 별도 컬럼이어야 하므로 `published_at` 을 따로 둔다.
  > 📄 p.2 "createdAt·publishedAt 기록 시각과 발행 확인 저장 시각 구분"
- relay 용 컬럼 `attempt_count`·`next_attempt_at`·`last_error` 는 과제 2절의 "최소 모델" 표에 포함되므로 지금 선언한다. 초기값은 `attemptCount = 0`, `lastError = null`, **`nextAttemptAt = 저장 시각(Clock 빈)`** 이다. 처음부터 다음 시각이 채워져 있으면 relay 가 "`status = PENDING` 이고 `next_attempt_at <= now`" 한 조건만으로 대상을 찾을 수 있고, "null 이면 즉시" 같은 특례 분기가 필요 없다. 값을 갱신하는 책임(시도 수 증가·다음 시각·오류 기록)은 relay 설계에서 정한다.
  > 📄 p.2 "다음 의미를 담는 최소 모델을 만듭니다. … attemptCount·nextAttemptAt·lastError 재시도·지연·실패 관찰"
  > 📄 p.3 "재시도 시간이 된 PENDING을 작은 묶음으로 안정된 순서에서 찾습니다."

### D16. 직렬화 실패 → 런타임 예외로 감싸 같은 TX rollback, `ObjectMapper` 는 주입
- `applyFinal` 안에서 직렬화하고 `JsonProcessingException` 을 런타임 예외(`CoreException`)로 바꿔 던진다. `@Transactional` 기본 규칙이 런타임 예외에 rollback 하므로 주문 CONFIRMED·예약 소비·Outbox 가 함께 되돌아간다. 체크 예외를 그대로 던지면 rollback 되지 않으니 반드시 감싼다.
  > 📄 p.3 "Outbox 저장에 필요한 serialization이 실패한 경우도 같은 TX의 실패로 다룹니다."
  > 📄 p.2 "Outbox 실패가 발생하면 최초 성공 반영·예약 소비도 rollback합니다. 원래 미완료 주문·이전 시도·예약은 TX 직전 상태로 남습니다. 기존 주문 행을 0개로 만드는 것이 기대 결과가 아닙니다."
- rollback 뒤 외부 PG 는 SUCCESS 그대로이므로 기존 reconcile·스케줄러의 같은 거래 재조회 경로로 다시 적용되며 create 는 추가되지 않는다 (D20 의 전환 테스트로 확인).
  > 📄 p.2 "외부 PG SUCCESS는 로컬 rollback과 무관하게 남을 수 있습니다. 같은 거래 재조회로 성공 반영을 재시도하고 create는 추가하지 않습니다."
- `ObjectMapper` 는 `PaymentResultService` 생성자로 주입받아 테스트에서 실패를 주입할 수 있게 한다. domain 이 Jackson 라이브러리에 의존하게 되지만 ArchUnit 규칙은 패키지 기준(interfaces·application·infrastructure)이라 위반이 아니다.

### D17. 직접 발행 제거 → `PaymentFacade` 에서 `OrderConfirmedPublisher` 의존·`publishAfterCommit` 삭제, `PaymentApplyResult.firstConfirmation` 은 유지
- 제품 코드에서 발행기를 부르는 곳은 `PaymentFacade` 한 곳이다. 필드·생성자 인자·`publishAfterCommit`·`isFirstConfirmation()` 분기를 지우면 제품 경로에서 직접 send 가 사라진다.
  > 📄 p.1 "W6의 after-commit 직접 send를 제거하거나 이 경로에서 호출되지 않게 교체합니다. 직접 send와 relay를 의도치 않게 병행하지 않습니다."
- `applyFinal` 의 반환값 `PaymentApplyResult.firstConfirmation` 은 그대로 둔다. 단위 테스트(`PaymentResultServiceTest` 등 6곳)가 이 값으로 "처음 확정" 을 확인하고 있고, Outbox 에 넣은 payload 와 같은 객체라 검증에도 쓸 수 있다.
- 생성자가 바뀌므로 `new PaymentFacade(...)` 를 직접 만드는 `PaymentReconcileIntegrationTest`·`PaymentScheduledLookupIntegrationTest` 의 인자 목록을 맞춘다. `PaymentResultServiceTest`(순수 단위)는 새 협력자(`OrderConfirmedOutboxRepository`, `ObjectMapper`)의 `@Mock` 추가까지만 한다.

### D18. `KafkaOrderConfirmedPublisher`·`OrderConfirmedPublisher`·`OrderConfirmedPublishException` → 이번엔 그대로 둠
- 제품 경로에서는 호출자가 없어지지만 클래스는 유지하고, 클래스 주석에 "W7 제품 경로 미사용, relay 설계(3절)에서 재결정" 을 명시한다. relay 가 "저장된 key·payload 를 그대로 보내는 전송기" 를 새로 가질지 이 발행기를 바꿀지는 3절에서 정한다.
  > 📄 p.3 "주문 확정 TX 밖에서 같은 topic·key·payload를 send합니다."
- 유지하는 이유: `KafkaOrderConfirmedPublisherTest`(계약 JSON·send 결과 분류)와 crossAppTest 의 W6 보존 경로가 이 발행기에 위임하고 있어, 지금 바꾸면 이번 범위 밖의 수정이 함께 들어간다.
- relay 설계에서의 결론(D27): 저장된 key·payload 를 보내는 전송기를 **새로** 두고, 이 발행기는 계속 그대로 둔다.

### D19. `OrderConfirmedDeliveryGapTest` → `W6GapReproductionAndRecoveryTest` 로 흡수하고 삭제
- 삭제 사유는 "검사가 실패하니까" 가 아니다. 이 테스트가 검증하던 대상(Facade 의 commit 뒤 직접 발행)이 과제 요구(D17)로 제품 코드에서 사라지기 때문이다. 그 테스트가 증명하던 사실 중 아직 유효한 것은 아래처럼 새 테스트가 같은 단언으로 이어받는다. W6 관찰값은 W6 설계 문서 8절과 streamer 의 녹화본(`experiments/delivery-gap-case2.json`, `OrderConfirmedReplayTest`)에 그대로 남는다.
- `@Disabled` 유지는 택하지 않았다. Facade 생성자가 바뀌어 어차피 손을 대야 하고, 실행되지 않는 테스트가 남는 것은 과제 p.7 의 태도와 거리가 있다.
  > 📄 p.7 "테스트 0건·skip·연결 실패를 통과로 세지 않습니다."

| 기존 `OrderConfirmedDeliveryGapTest` 의 단언·기록 | 새 테스트에서 |
|---|---|
| 사례 1: 주문 CONFIRMED·confirmedAt 고정값·예약 CONSUMED·시도 SUCCESS | 있음 (`assertConfirmed`) |
| 사례 1: `publishCallCount()==1`, `published()` 비어 있음, broker 에 해당 key 없음 | 있음 |
| 사례 2: broker record 1건, key = orderId, eventId·confirmedAt 일치 | 있음 |
| 사례 2: value JSON 의 `orderId`, `items[0]`=상품A×2, `items[1]`=상품B×1 | **이번에 옮김** |
| 사례 2: 예약 CONSUMED, `createCount()==2` | 있음 |
| 응답 `settled()` | W6 보존본은 응답 DTO 를 만들지 않으므로 주문 CONFIRMED 단언으로 대체 |
| `build/delivery-gap/observation.json` 기록 | `build/w7-recovery/observation.json` 으로 대체. W6 기록·녹화본은 그대로 |
| "Facade 가 commit 뒤 발행한다" | W7 에서 의도적으로 사라지는 사실. 대신 D20 의 "Outbox 가 같은 TX 에 생긴다" 가 들어옴 |

### D20. `OrderConfirmedPublicationTest` 7개 → `OrderConfirmedOutboxAtomicityTest` 로 이름을 바꾸고 "Outbox 가 생긴다/안 생긴다" 단언으로 전환
- 새 이름은 과제 p.7 의 검사 예시와 맞춘다. 내용이 "발행" 이 아니라 "확정 TX 와 Outbox 의 로컬 원자성" 이 되므로 옛 이름은 어색하다.
  > 📄 p.7 "./gradlew :apps:commerce-api:test --tests '*OutboxAtomicityTest'"
- git 이 rename 으로 추적하도록 **이름만 바꾸는 변경과 내용을 바꾸는 변경을 분리**해 작업한다(커밋도 둘로 나눌 수 있게). 이름 변경 단계에서는 클래스 이름과 파일 이름만 바꾸고 단언은 그대로 둔다.
- 발행 여부 대신 Outbox 행의 존재·내용·상태를 단언한다. 가짜 발행기(`FakeOrderConfirmedPublisherConfig`) import 는 빼고, Outbox 리포지터리와 별도 연결(`JdbcTemplate`)로 읽는다. 클래스 주석은 과제 2절 기준으로 고친다.
- 실패 주입은 기존 테스트가 `StockReservationRepository` 에 쓰는 것과 같은 `@MockitoSpyBean` 방식이다. 직렬화 실패는 `ObjectMapper` spy 에서, **`OrderConfirmedEvent` 인스턴스를 직렬화할 때만**(`writeValueAsString(any(OrderConfirmedEvent.class))`) 예외를 던지게 인자 조건을 좁힌다. `ObjectMapper` 는 웹 응답·Kafka 발행기 등 다른 곳에서도 쓰이므로 조건 없이 던지면 테스트가 다른 경로에서 먼저 깨진다. 저장 실패는 `OrderConfirmedOutboxRepository` spy 의 `save` 에서 던진다.

| 기존 테스트 | 기존 단언 | 전환 후 단언 |
|---|---|---|
| `publishesOnce_afterCommit` | 이벤트 1건 발행, 발행 시점 활성 TX 없음, 별도 연결로 읽은 주문 CONFIRMED | Outbox 1행: eventId·orderId·messageKey·eventType·schemaVersion·status PENDING·publishedAt null, payload JSON 이 저장 품목·confirmedAt(ISO 문자열)과 일치. 별도 연결로 읽어도 주문 CONFIRMED 와 Outbox 1행이 함께 보인다 |
| `publishesOnlyOnce_whenCallbackAndReconcileRepeat` | 중복 callback·reconcile 에도 이벤트 1건, 재고 차감 1회, create 1회 | Outbox 1행 유지, 재고·create 단언 그대로 |
| `nothing_whenPending` | 발행 0회, 주문 PAYMENT_PENDING | Outbox 0행, 주문 PAYMENT_PENDING |
| `nothing_whenUnknown` | 발행 0회, 시도 PENDING, 주문 PAYMENT_PENDING | Outbox 0행, 나머지 그대로 |
| `nothing_whenFailed` | 발행 0회, 두 주문 PAYMENT_FAILED, confirmedAt null | Outbox 0행, 나머지 그대로 |
| `nothing_whenConfirmationRollsBack_andExceptionPropagates` | 예약 갱신 예외 → 발행 0, 주문 PAYMENT_PENDING, 재시도 후 발행 1건 | 예약 갱신 예외 → Outbox 0행, 주문 PAYMENT_PENDING, 재시도 후 Outbox 1행, create 1회 |
| `keepsOrder_whenPublishFails` (FAILED) | 발행 실패에도 주문 CONFIRMED·예약 CONSUMED 유지 | **직렬화 실패 주입**(`ObjectMapper` spy) → 확정 TX 전체 rollback: 주문 PAYMENT_PENDING·confirmedAt null, 예약 RESERVED, 재고 미차감, Outbox 0행, 예외 전파, create 추가 없음. 주입 해제 뒤 재시도 → CONFIRMED·CONSUMED·Outbox 1행 |
| `keepsOrder_whenPublishUnknown` (UNKNOWN) | 같음 | **Outbox 저장 실패 주입**(리포지터리 spy) → 같은 rollback 결과와 재시도 성공 |

- 마지막 두 테스트의 의미가 바뀌는 이유: W7 제품 경로에는 "발행 실패" 장면이 없고, 대신 과제 2절이 요구하는 "Outbox 실패 시 확정도 rollback" 과 "기존 주문 행을 0개로 만들지 않는다(PAYMENT_PENDING 으로 남는다)" 를 같은 자리에서 검증한다.
  > 📄 p.2 "Outbox 실패가 발생하면 최초 성공 반영·예약 소비도 rollback합니다. 원래 미완료 주문·이전 시도·예약은 TX 직전 상태로 남습니다."

### D21. W6 보존본(`W6DirectPublishPath`) → 수정 없음, 새 테스트의 "Outbox 없음" TODO 를 리포지터리 조회로 채움
- 보존본은 `PaymentFacade`·`PaymentResultService` 를 호출하지 않고 리포지터리 인터페이스·PG·발행기·Clock·트랜잭션 관리자만 쓴다. D17 의 Facade 변경과 D10 의 `applyFinal` 변경 모두 보존본에 닿지 않으며, 보존본은 Outbox 를 쓰지 않으므로 "확정은 됐지만 Outbox 는 없는" W6 상태를 계속 만든다. D18 로 발행기도 그대로라 위임 경로도 불변이다.
- 새 테스트의 TODO 는 `OrderConfirmedOutboxRepository` 로 두 주문의 Outbox 가 없음을 새 DB 조회로 확인하는 단언으로 채운다. `DatabaseCleanUp` 은 메타모델 기준이라 Outbox 테이블도 테스트 끝 정리에 자동 포함된다.
  > 📄 p.4 5-2 "두 주문 모두 CONFIRMED·예약 소비가 있고 Outbox는 아직 없으며, 첫 주문의 구매 활동만 0인지 새 DB 조회로 확인합니다."

---

## 3. 과제 3~4절 결정 · 단일 relay 와 재시도·운영 재개

이 절의 범위는 과제 3절(단일 relay)·4절(재시도·운영 재개)이다. 복구(과제 5-4~5-6)와 과제 6절 중단 실험은 이 문서 4절에서 따로 정한다.

클래스 배치(기존 계층 관례와 ArchUnit 규칙에 맞춤):
- `application/order/OrderConfirmedOutboxRelay` — 한 회차(`runOnce()`)·재개(`resume(eventId)`) 로직. 트랜잭션은 리포지터리 호출 단위로 짧게 연다
- `application/order/OutboxMessageSender` — "저장된 key·payload 를 그대로 보내는 전송기" 인터페이스 (D27)
- `application/order/OutboxRelayProperties` — `outbox.relay.*` 설정 (D28)
- `infrastructure/order/KafkaOutboxMessageSender` — Kafka 구현
- `interfaces/scheduler/OutboxRelayScheduler` — 주기 진입점 (D22). 기존 `PaymentReconcileScheduler` 와 같은 모양
- `OrderConfirmedOutboxRepository` 에 relay 용 메서드 추가: PENDING 묶음 조회, 시도 시작·발행 완료·재시도 예약·BLOCKED 전이·재개의 조건부 UPDATE (D23·D25·D29)

### D22. 실행 방식 → `@Scheduled(fixedDelay)` + relay 전용 단일 스레드 `TaskScheduler` + `outbox.relay.enabled` + 프로세스 안 `tryLock`
- 기존 `PaymentReconcileScheduler` 와 같은 모양으로 `interfaces/scheduler` 에 진입점을 두고, `enabled=false` 면 빈은 만들되 실행만 건너뛴다. test 프로파일은 `enabled=false` 로 두고 테스트가 `runOnce()` 를 직접 호출한다.
- Spring 기본 스케줄러는 스레드 1개를 모든 `@Scheduled` 가 나눠 쓰므로 결제 조회 스케줄러와 relay 가 서로를 지연시킬 수 있다. relay 전용 단일 스레드 `TaskScheduler` 빈을 만들어 `@Scheduled(scheduler = …)` 로 지정한다. `fixedDelay` 는 앞 실행이 끝난 뒤 간격을 세므로 같은 tick 은 겹치지 않는다.
- `TaskScheduler` 빈을 하나라도 정의하면 Boot 의 기본 `taskScheduler` 자동 설정이 꺼지므로, 기본 스케줄러도 같은 `ThreadPoolTaskSchedulerBuilder` 로 다시 만들고 `@Primary` 를 붙인다. `scheduler` 를 지정하지 않은 기존 `@Scheduled`(결제 조회)는 계속 기본 스케줄러를 쓰고 `spring.task.scheduling.*` 설정도 그대로 적용된다.
- 스케줄 tick 과 수동 실행(`resume`·테스트의 `runOnce`)이 한 프로세스 안에서 겹치지 않게 relay 안에 `ReentrantLock.tryLock()` 을 둔다. 잡지 못하면 그 회차는 "이미 실행 중" 로그를 남기고 돌아간다.
- `runOnce()` 는 회차 결과(`OutboxRelayResult`)를 값으로 돌려준다: 집은 행 수(`picked`), 발행 성공(`published`), 재시도 예약(`retried`), BLOCKED(`blocked`), 종료 사유(`reason`: COMPLETED / BROKER_UNAVAILABLE / ALREADY_RUNNING), 그리고 건너뜀을 둘로 나눈 값. **`skippedStale`** = 시도 직전에 상태가 이미 바뀌어 보내지 않은 행(단일 relay 에서는 0 이어야 하며, 0 이 아니면 다른 실행자가 있다는 이상 신호라 WARN 로그로 드러낸다). **`deferred`** = broker 불가로 회차를 일찍 끝내 다음 회차로 미룬 행(정상). `remainingPending` 은 회차 끝의 PENDING 수이며, ALREADY_RUNNING 일 때는 집지도 세지도 않았다는 뜻으로 **-1** 이다. 테스트 단언과 D31 의 요약 로그가 **같은 값**을 쓴다.
  > 📄 p.3 "한 번에 한 relay 실행 흐름만 활성화합니다. scheduler·수동 실행이 겹치지 않게 하고, 다른 인스턴스에서 같은 relay를 동시에 켜지 않는 로컬 실행 설정을 정합니다. 여러 프로세스의 소유권 문제를 메모리 flag로 해결했다고 주장하지 않습니다."
- **명시:** 메모리 잠금은 한 프로세스 안의 보호일 뿐이다. 여러 인스턴스가 같은 relay 를 동시에 켜지 않는 것은 인스턴스별 `outbox.relay.enabled` 설정으로만 막는다. 분산 잠금을 구현했다고 주장하지 않는다.
  > 📘 학습 p.6 "단일 relay에도 여러 scheduler tick이 겹치면 실행자가 둘인 것처럼 동작합니다. 한 번에 한 루프만 돌게 하고, 다중 인스턴스에서 동시에 켜지지 않도록 실행 설정을 정합니다. 이를 다중 relay 환경의 분산 잠금 보장이라고 주장하지 않습니다."

### D23. 한 회차 구조 → 조회 TX → 행마다 [시도 시작 TX → TX 밖 send 대기 → 결과 TX]
- 과제 3절의 1~5 단계와 1:1 로 맞춘다. ① 조회 TX: `status = PENDING AND next_attempt_at <= now` 를 `next_attempt_at, id` 순으로 묶음 크기만큼 읽는다(읽기만). ② 행마다 시도 시작 TX: `attempt_count + 1` (조건 `status = PENDING`). ③ TX 밖: 저장된 messageKey·payload 를 그대로 send 하고 결과를 기다린다. ④ 결과 TX: 성공이면 `PUBLISHED`·`publishedAt`, 일시 실패·timeout 이면 `nextAttemptAt`·`lastError`, 영구 오류면 `BLOCKED`·`lastError`.
  > 📄 p.3 "1. 재시도 시간이 된 PENDING을 작은 묶음으로 안정된 순서에서 찾습니다. 2. 대상 eventId와 저장 payload로 시도를 시작하고 필요한 시도 정보를 남깁니다. 3. 주문 확정 TX 밖에서 같은 topic·key·payload를 send합니다. 4. 실제 broker 성공 확인 뒤 별도 DB TX에서 해당 행을 PUBLISHED로 바꿉니다. 5. 실패·timeout이면 동일 행을 재시도 대상으로 유지하고 다음 시각·오류를 기록합니다."
- 전송 대기 중에는 DB 커넥션·행을 붙잡지 않는다. 묶음 전체를 한 TX 로 묶는 방식은 이 원칙에 어긋나 택하지 않았다. 시도 시작 TX 를 생략하는 방식은 전송 중 중단 때 흔적이 남지 않아 과제 2단계와 어긋난다.
  > 📄 p.3 "DB 조회·상태 변경 TX와 외부 전송 대기를 분리합니다. 단일 relay의 직렬 실행을 기본으로 두고, 무제한 future 병렬 생성이나 tick 중첩으로 범위를 넓히지 않습니다."
- `attemptCount` 는 "시도를 시작한 횟수" 다. broker 수신 수가 아니다.
  > 📘 학습 p.3 "attemptCount 가 '실제 broker 수신 수'는 아닙니다. 시도를 시작할 때 올리면 전송 전에 중단될 수도 있습니다."

### D24. 일시 오류(broker 불가) → 그 회차 종료, 영구 오류 → 그 행만 BLOCKED 처리 후 다음 행 계속
- send 가 전송 실패(FAILED)나 timeout(UNKNOWN) 으로 끝나면 broker 가 지금 응답하지 않는다는 뜻이므로, 그 행의 결과 TX(재시도 예약)만 기록하고 **그 회차를 끝낸다.** 남은 PENDING 은 다음 tick 에 다시 집는다. 묶음 크기만큼 타임아웃을 연속으로 기다리는 일(20건 × 3초)을 피한다. 기존 결제 조회가 "서킷이 열리면 이번 주기를 중단" 하는 태도와 같다.
- 특정 행만의 영구 오류(D29 의 즉시 BLOCKED 목록)는 broker 상태와 무관하므로 그 행을 BLOCKED 로 바꾸고 **다음 행을 계속** 처리한다.
  > 📄 p.3~4 "실패 뒤 즉시 무한 반복하지 않도록 전송 대기 시간·한 번의 처리 개수·재시도 간격을 정합니다." "broker 장애가 회복되면 기존 PENDING을 다시 처리합니다."

### D25. "처리 중" 표시(lease) 없음, 늦은 callback 은 등록하지 않음, 상태 UPDATE 는 모두 `status = PENDING` 조건
- 단일 relay 가 직렬로 돌므로 같은 행을 동시에 집는 실행자가 없다. lease 컬럼·`PUBLISHING` 상태는 두지 않는다 (다중 relay 로 갈 때 claim 설계를 새로 해야 한다는 점을 한계로 남긴다).
- 늦은 callback 차단: producer future 에 콜백(`whenComplete` 등)을 달지 않는다. relay 는 `get(대기 상한)` 의 결과만 해석하고, 상태 변경은 relay 의 결과 TX 한 곳에서만 한다. 대기를 넘긴 send 가 뒤에서 성공해도 상태를 건드릴 코드 경로가 없다.
  > 📄 p.3 "로컬 대기를 중단해도 하위 send가 나중에 성공할 수 있습니다. 늦은 callback이 상태를 임의 변경하지 않게 하고, 상태 변경 책임을 relay의 한 경계에 둡니다."
- 공통 규칙 두 가지. (1) 결과 TX 의 UPDATE 는 모두 `WHERE id = ? AND status = 'PENDING'` 조건을 단다. 이미 PUBLISHED·BLOCKED 가 된 행을 늦게 덮어쓰지 못한다. (2) timeout(UNKNOWN) 은 "수신 0회" 로 단정하지 않는다. PENDING 으로 남겨 같은 eventId 가 재발행될 수 있음을 받아들이고, 중복은 W6 소비자의 장부가 막는다.
  > 📄 p.3 표 "timeout·응답 유실 같은 사건 → PENDING, 수신 0회라고 단정하지 않음 / broker 성공 뒤 상태 TX 실패 → PENDING이 남아 같은 사건 재발행 가능"
  > 📘 학습 p.4 "타임아웃마다 새 eventId를 만들면 같은 사건의 중복 처리가 깨집니다."

### D26. send 대기 → 동기 `future.get(대기 상한)` 유지 (W6 D7 재검토 결론)
- W6 D7 은 "W7 Outbox relay 설계 시 비동기 재검토" 를 남겼다. 재검토 결론은 **동기 유지**다. 이유: 전송 확인은 future 의 성공이어야 하고, 결과 분류(성공/실패/미확인)를 행마다 바로 결과 TX 에 반영해야 D25 의 "상태 변경 책임 한 곳" 이 지켜진다. 과제 6절의 "broker 확인 뒤 PUBLISHED 저장 전 중단" hook 도 동기 흐름에서 자연스럽다.
  > 📄 p.3 "전송 확인은 send 호출이 예외 없이 반환했다는 뜻이 아니라 producer future의 성공입니다. acks 는 broker 확인을 받을 수 있는 설정을 선택하고 W6의 단일 broker 한계를 유지합니다."
- 묶음을 한꺼번에 보내고 future 들을 모아 기다리는 방식은 과제가 경고한 "무제한 future 병렬 생성" 에 가깝고 중단 실험의 경계가 흐려져 택하지 않았다. 완전 비동기 콜백은 D25 와 정면 충돌한다.
- **한계:** 행마다 최대 대기 상한만큼 기다리므로 처리량이 낮다(묶음 20 × 3초 = 회차 최대 60초). 이번 과제 규모(주문당 이벤트 1건, 로컬 단일 broker)에서는 문제가 아니며, 처리량이 중요해지면 제한된 병렬·여러 relay 와 함께 claim 설계를 다시 해야 한다.

### D27. 전송기 → 새 인터페이스 `OutboxMessageSender(messageKey, payload)` + `KafkaOutboxMessageSender`, 기존 발행기는 그대로
- relay 는 Outbox 행의 `messageKey` 와 `payload` 문자열을 UTF-8 bytes 로 바꿔 그대로 보낸다. `OrderConfirmedEvent` 객체를 다시 만들지 않고 현재 상품도 조회하지 않는다. 입력 모양이 "객체" 인 기존 `KafkaOrderConfirmedPublisher` 와 다르므로 전송기를 새로 둔다. topic·`KafkaTemplate<String, byte[]>`(`orderEventKafkaTemplate`) 는 기존 것을 함께 쓴다.
  > 📄 p.3 "주문 확정 TX 밖에서 같은 topic·key·payload를 send합니다." "relay가 현재 상품 정보를 재조회해 payload를 다시 만들지 않습니다."
- 결과는 W6 D7 과 같은 세 가지(성공 metadata / FAILED / UNKNOWN)로 분류한다. 분류 로직의 공통화는 **기존 발행기의 동작과 테스트가 바뀌지 않는 범위에서만** 한다. 공통화가 기존 클래스 수정을 요구하면 중복을 두는 쪽을 택한다.
- 기존 발행기를 바꾸거나 지우는 안은 계약 테스트와 crossAppTest 의 W6 보존 경로 수정을 끌고 와 택하지 않았다 (D18).

### D28. 시작값 → 대기 3초 · 묶음 20 · 회차 간격 10초 · backoff 10초 × 2배, 상한 5분. test 는 짧게
- **측정 없이 정한 시작값**이다. 기존 설정과 Kafka 기본값에서 끌어냈고, 측정 실험은 이번 범위 밖이다.

| 설정 (`outbox.relay.*`) | 운영 시작값 | test 프로파일 | 근거 |
|---|---|---|---|
| `send-timeout` | 3초 | 1초 | 기존 `order-events.send-timeout-ms` 와 같게. `max.block.ms` 도 같은 값이어야 metadata 대기가 같은 상한에 걸린다 (W6 D7). **test 에서만 다르다:** 전송기가 기존 `orderEventKafkaTemplate` 을 쓰므로 `max.block.ms` 는 `order-events.send-timeout-ms`(3초)에 묶여 있고, relay 대기만 1초다. 운영값은 둘 다 3초라 원칙과 맞고, test 의 "닿지 않는 broker" 는 500ms 짜리 별도 템플릿을 써서 영향이 없다 |
| `batch-size` | 20 | 10 | 직렬 실행이라 "묶음 × 대기 상한" 이 회차 최대 길이. 20 × 3초 = 60초 |
| `fixed-delay` | 10초 | (자동 실행 꺼짐) | 정상 상황에서 PENDING 이 머무는 시간. 결제 조회(30초)보다 짧게 둬 발행 지연을 줄인다 |
| `initial-backoff` / `multiplier` / `max-backoff` | 10초 / 2 / 5분 | 0초 / 1 / 0초 | 아래 |
| `enabled` | true | false | test 는 `runOnce()` 직접 호출 |

- backoff 상한을 5분으로 **짧게** 둔 이유: broker 장애가 길어져 간격이 상한까지 늘어난 행도, broker 가 회복되면 최대 5분 안에 다시 시도되어 기존 PENDING 재처리가 오래 지연되지 않는다. 결제 조회의 30분 상한을 그대로 쓰면 회복 뒤에도 최대 30분 동안 발행이 멈춘 것처럼 보인다.
  > 📄 p.4 "broker 장애가 회복되면 기존 PENDING을 다시 처리합니다."
- test 값이 운영과 다른 이유:
  > 📄 p.3~4 "테스트에서는 시간을 통제하거나 짧은 유한 대기로 검증하고 운영 값을 무조건 같은 수준으로 두지 않습니다."
- 기존 `payment.lookup` 이 test 에서 `PT0S`·배수 1 을 쓰는 것과 같은 방식이다.
- **예외 하나:** backoff 계산 규칙 자체를 검증하는 테스트 하나는 test 값이 아니라 **운영 시작값(초기 10초 × 2배, 상한 5분)** 을 명시해 쓰고, 고정 `Clock` 으로 시각을 통제해 `nextAttemptAt` 이 `now + 10초 → 20초 → 40초 → … → 5분 상한` 으로 늘어나는지 정확히 단언한다. 시간을 통제하므로 실제로 기다리지 않는다.
  > 📄 p.3~4 "테스트에서는 시간을 통제하거나 짧은 유한 대기로 검증하고 운영 값을 무조건 같은 수준으로 두지 않습니다."

### D29. 영구 오류 → `BLOCKED` 도입. 즉시 BLOCKED 는 네 계열만, 일시 오류는 상한 없이 backoff 재시도, 재개는 BLOCKED → PENDING + 한 회차
- **즉시 BLOCKED (다시 보내도 같은 결과인 오류):** `RecordTooLargeException`, `SerializationException`, `InvalidTopicException`, `AuthorizationException` 계열. 그 행만 `BLOCKED`·`lastError` 로 바꾸고 다음 행을 계속한다 (D24). ERROR 로그에 원인과 eventId 를 남긴다.
  > 📄 p.4 "반복되는 영구 오류는 원인과 eventId를 노출합니다. 별도 BLOCKED 상태로 분리했다면 미해결 상태로 목록에 남기고 복구 후 같은 행을 재개하는 경로를 둡니다."
- **일시 오류 (`TimeoutException`, `NotEnoughReplicasException`, `NetworkException`, 그 밖의 `KafkaException`, 그리고 `UnknownTopicOrPartitionException`):** `nextAttemptAt` 을 backoff 로 미루고 PENDING 유지. **시도 상한으로 BLOCKED 하지 않는다.** 이유: 상한에 닿아 BLOCKED 가 되면 broker 장기 장애 뒤 사람이 재개하지 않는 한 자동 재처리가 안 되어 과제 p.4 "broker 장애가 회복되면 기존 PENDING을 다시 처리합니다" 와 어긋난다. `UnknownTopicOrPartition` 은 topic 미생성·메타데이터 지연 같은 운영 상황이 풀리면 해결되므로 일시 쪽에 둔다.
  > 📘 학습 p.5 표 "broker 일시 장애 → 간격을 둔 제한된 시도, 이후 재개 / 피할 행동: 빠른 무한 루프" "DB 상태 갱신 오류 → PENDING 유지·같은 사건 재발행 / 피할 행동: 새 eventId로 회피"
- **재개 경로:** `resume(eventId)` 가 해당 행을 `BLOCKED → PENDING`(`nextAttemptAt = now`, 조건 `status = BLOCKED`)으로 되돌린 뒤 한 회차(`runOnce()`)를 실행한다. 추측하지 않고 오류로 드러낸다: 없는 eventId 는 `NOT_FOUND`, 있지만 BLOCKED 가 아닌 행(PENDING·PUBLISHED)은 `CONFLICT`. 어느 쪽도 상태를 바꾸지 않는다.
- BLOCKED·PENDING 행을 자동 삭제하지 않는다. 보존·정리 정책은 소비자 장부(`processed_order_confirmation`) 보존 기간과 함께 정해야 하며, 이번 과제에서는 "삭제 없음" 으로 둔다.
  > 📄 p.4 "이번 과제에서는 미발행·격리 행을 자동 삭제하지 않습니다. 보존·정리 정책과 dedup 기록 보존의 관계를 짧게 정리합니다."
  > 📘 학습 p.6 "영구 오류를 BLOCKED 등으로 분리하면 정상 대상이 계속 진행할 수 있지만, 그 행은 완료가 아니라 사람이 다시 판단해야 할 미해결 작업입니다."
- `PUBLISHED` 는 broker 확인을 저장했다는 뜻이고 소비 완료가 아니다.
  > 📄 p.4 "PUBLISHED를 소비 완료로 표시하지 않습니다. consumer lag·오류·실제 구매 활동을 따로 확인합니다."

### D30. 로컬 운영 진입점 → 테스트 도구만. 제품 진입점 없음
- relay 서비스의 `runOnce()`·`resume(eventId)` 를 crossAppTest·통합 테스트가 직접 호출한다. HTTP 엔드포인트·`ApplicationRunner`·batch 앱 진입점은 만들지 않는다.
  > 📄 p.4 "재개는 로컬에서 대상 eventId를 명시해 relay 한 회차를 실행하는 작은 운영 진입점이나 테스트 도구로 충분합니다. 이 진입점은 로컬에서만 사용하며 공개 관리자 API로 노출하지 않습니다."
- `/api-admin/**` 아래 노출은 과제가 금지해 택하지 않았다. 실행 인자 방식은 웹 앱에 "실행 후 종료" 모드를 더해 설정 분기가 늘어 택하지 않았다.

### D31. 관찰 → SQL 조회문 + 회차 요약 로그 1줄
- 설계 문서에 조회문을 남긴다: PENDING 수·가장 오래 기다린 시각·최대 시도 수는 `SELECT COUNT(*), MIN(created_at), MAX(attempt_count) FROM order_confirmed_outbox WHERE status = 'PENDING'`, BLOCKED 목록은 `SELECT event_id, attempt_count, last_error FROM order_confirmed_outbox WHERE status = 'BLOCKED'`.
- relay 는 회차 끝에 요약 로그 1줄을 `key=value` 로 남긴다: `reason`·`picked`·`published`·`retried`·`blocked`·`skippedStale`(이상 신호)·`deferred`(미룸)·`pendingRemaining`. 이 값들은 `runOnce()` 가 돌려주는 회차 결과 객체(D22)에서 그대로 가져오므로 테스트가 단언하는 값과 로그 값이 같다. `skippedStale` 이 0 이 아닌 회차는 WARN 로그가 함께 남는다. local·test 는 평문 콘솔, dev 이상은 기존 logstash JSON 콘솔에 그대로 실린다. 새 모니터링 서버·Micrometer 지표는 두지 않는다.
  > 📄 p.4 "전송 횟수·PENDING 수·가장 오래 기다린 시각을 SQL이나 간단한 구조화 로그로 확인합니다. 새 모니터링 서버는 필수가 아닙니다."

### D32. PENDING 조회 인덱스 → `(status, next_attempt_at, id)`
- 조회가 `WHERE status = 'PENDING' AND next_attempt_at <= ? ORDER BY next_attempt_at, id LIMIT n` 이므로 같은 순서의 복합 인덱스를 `@Table(indexes = @Index(name = "idx_order_confirmed_outbox_status_next_attempt", columnList = "status, next_attempt_at, id"))` 로 선언한다. 기존 `Like`·`Product` 의 `@Index` 관례와 같고 test 의 `ddl-auto: create` 가 만든다.
  > 📄 p.3 "PENDING 조회에는 상태·다음 시각·정렬 조건에 맞는 인덱스를 검토합니다."
- 한계: PUBLISHED 가 쌓이면 인덱스도 커진다. 보존 정책(D29)과 함께 봐야 한다.

### D33. 오류 메시지 → 예외 클래스·요약 메시지·eventId·orderId·attemptCount 만, payload·접속 정보 제외
- `lastError` 와 로그에는 예외 클래스 이름 + 메시지 요약 + eventId·orderId·attemptCount(성공 시 topic·partition·offset)만 남긴다. payload 전체는 적지 않는다(DB 에 이미 있다). 메시지에 bootstrap 주소·인증 설정 같은 접속 정보가 섞여 오면 제거하고, 컬럼 길이(1000자)에 맞춰 자른다.
  > 📄 p.3 "오류 메시지에는 카드·비밀·불필요한 전체 payload를 남기지 않습니다."
- payload 자체에 카드 번호는 없다(`OrderConfirmedEvent` 는 eventId·orderId·confirmedAt·items 만). 그래도 "불필요한 전체 payload" 를 로그에 복사하지 않는 원칙은 지킨다.

---

## 4. 복구·중단 실험 결정 (미정, 다음 단계)

과제 5-4~5-6(복구)과 6절(두 중단 구간 검증)·7절(E2E)을 구현하기 전에 여기에 D34 부터 이어서 적는다.

---

## 5. 실험 관찰

### 5-1. 준비(5-1~5-3) 실행 (2026-11-06)

Testcontainers MySQL 8.0·Kafka 3.8.1, 격리 topic `order-events-W6GapReproductionAndRecoveryTest`, group `purchase-activity-W6GapReproductionAndRecoveryTest`.

| 항목 | 주문 A (commit 후 send 전 중단) | 주문 B (발행·소비 완료) |
|---|---|---|
| orderId / providerOrderId | 1 / `LP-ORD-0000000001` | 2 / `LP-ORD-0000000002` |
| PG 거래 ID | `FAKE-TX-000001` | `FAKE-TX-000002` |
| eventId | `order-confirmed:v1:1` | `order-confirmed:v1:2` |
| confirmedAt | `2026-11-07T01:00:00.123Z` | 같음 |
| items | 상품 1 × 2, 상품 2 × 1 | 같음 |
| 주문 / 시도 / 예약 | CONFIRMED / SUCCESS / CONSUMED ×2 | 같음 |
| broker record | 없음 (topic 전체를 raw consumer 로 확인) | partition 0 offset 0, key `"2"` |
| 구매 활동 (새 DB 조회) | 장부 0행, 활동 0행 | 장부 1행, 활동 2행 (상품 1 × 2, 상품 2 × 1) |

PG create C = 2, 외부 거래 T = 2. 복구 단계는 이 값에서 증가 0 을 확인해야 한다.

검증: `./gradlew :apps:commerce-api:crossAppTest` 1건 통과. `./gradlew :apps:commerce-api:test --rerun` 453건(skip 0, 실패 0, `@SpringBootTest` 36개 클래스 포함), `./gradlew :apps:commerce-streamer:check` 43건 통과. Checkstyle·ArchUnit 포함.
> 📄 p.7 "테스트 0건·skip·연결 실패를 통과로 세지 않습니다."

### 5-2. relay·재시도(과제 3~4절) 실행 (미정)

### 5-3. 복구(5-4~5-6) 실행 (미정)

### 5-4. 두 중단 구간 검증 · E2E (과제 6·7절, 미정)
