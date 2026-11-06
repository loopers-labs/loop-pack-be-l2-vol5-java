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

작업 순서: **1단계 = 과제 5절 준비(5-1~5-3)** 를 W6 경로로 먼저 만들어 누락 상태를 재현한다 (이 문서 1절). 그 뒤 Outbox 모델·relay·복구(과제 2~4절, 5-4~5-6)를 같은 테스트 메서드에 이어 붙인다 (2절, 미정).

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
- `build/` 는 `.gitignore` 대상이라 저장소에 들어가지 않는다. 제출용 수치는 이 문서 4절에 옮긴다.

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

## 2. Outbox 모델·relay·복구 결정 (미정, 다음 단계)

과제 2절(Outbox 모델과 로컬 원자성), 3절(단일 relay), 4절(재시도·운영 재개), 5-4~5-6(복구)을 구현하기 전에 여기에 D10 부터 이어서 적는다.

---

## 3. 열린 질문

- 기존 `OrderConfirmedDeliveryGapTest`: W7 에서 Facade 의 직접 발행이 사라지면 그대로는 깨진다. 삭제·`W6GapReproductionAndRecoveryTest` 로 흡수·W6 기록용 `@Disabled` 유지 중 선택이 필요하다. 검사 통과를 위한 삭제가 아니라는 이유를 남겨야 한다.
- `KafkaOrderConfirmedPublisher`: relay 가 재사용하면 보존 경로도 그대로 위임할 수 있다. relay 가 자체 send 를 갖고 이 클래스를 지우면 보존 경로와 `FakeOrderConfirmedPublisherConfig` 를 손봐야 한다.

---

## 4. 실험 관찰

### 4-1. 준비(5-1~5-3) 실행 (2026-11-06)

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

### 4-2. 복구(5-4~5-6) 실행 (미정)

### 4-3. 두 중단 구간 검증 · E2E (과제 6·7절, 미정)
