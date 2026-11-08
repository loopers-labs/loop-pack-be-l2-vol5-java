# W7 Transactional Outbox와 복구 채점 기준

2026-11-03 제공된 [과제 원문](assignment-original.md)을 이번 주 평가 기준으로, [학습 자료](learning-original.md)를 개념 근거로 사용한다. 두 파일은 첨부 원문을 그대로 보존한다. 해석이 다르면 과제 원문을 우선한다.

목표는 **최초 PG 확정과 Outbox를 함께 저장하고, 단일 relay의 중단·재발행 뒤에도 기존 구매 활동으로 수렴시키는 것**이다. W6의 주문·이벤트 계약·consumer를 유지하고 확정 후 직접 발행 경계를 교체한다. 공식 체크 항목은 **11개**이며 임의 배점·최소 테스트 개수·성능 합격선을 추가하지 않는다. 구현 상태와 실행 증거는 [W7 구현 체크리스트](implementation-checklist.md)에서 관리한다.

## 공식 11개 항목과 확인 증거

| ID | 원문의 체크 항목 | 충족을 확인할 증거 |
| --- | --- | --- |
| W7-C01 | 최초 PG 확정·예약 소비·Outbox insert가 같은 TX에서 commit/rollback한다. | 같은 데이터 소스·로컬 TX에서 주문·시도·예약·Outbox가 함께 저장됨. serialization 또는 insert 전후 실패와 실제 SQL 후 rollback. 별도 REQUIRES_NEW·after-commit insert 금지 |
| W7-C02 | 중복 결과 적용·실패·UNKNOWN에서 거짓 이벤트나 추가 결제가 생기지 않는다. | PENDING·UNKNOWN·FAILED의 OrderConfirmed Outbox 0. 같은 거래의 중복 callback/reconcile에도 최초 확정·예약 소비·eventId별 Outbox 한 번, 추가 PG create 0 |
| W7-C03 | 기존 주문의 rollback 결과를 신규 주문 삭제와 혼동하지 않는다. | 실패 후 기존 미완료 주문·시도·예약이 TX 직전 상태로 남고 새 Outbox는 0. 외부 PG SUCCESS와 로컬 rollback을 구분하고 같은 거래 재조회로 복구, 추가 create 없음 |
| W7-C04 | 단일 relay가 저장된 identity·payload를 그대로 보내며 broker 확인 후 PUBLISHED로 바꾼다. | 재시도 시각이 된 PENDING의 제한된 묶음·안정된 순서, 저장 key/payload 그대로 send, producer future 성공 metadata 뒤 별도 상태 TX. 외부 대기 중 DB TX 없음, tick·수동 실행 중첩 방지와 단일 인스턴스 실행 설정 |
| W7-C05 | commit 후 relay 전 중단과 broker 확인 후 상태 저장 전 중단을 검증했다. | 두 구간의 실제 DB·broker·활동 관찰. 같은 DB/Kafka/eventId에서 relay를 새로 만들어 저장 행으로 재개. broker 성공 metadata를 먼저 확보하고 PUBLISHED 저장 전 중단하며 상태를 인위적으로 되돌리지 않음 |
| W7-C06 | 실제 broker·consumer에서 재발행돼도 상품별 구매 효과가 늘지 않는다. | 기존 주문 API·로컬 PG→Outbox→첫 broker 확인 뒤 중단→relay 재시작→실제 consumer의 E2E. 동일 eventId의 별도 broker record·소비 수신 2회 이상, 완료 1건·품목별 활동 불변, Outbox 1행 최종 PUBLISHED |
| W7-C07 | 새 격리 DB에서 W6 누락·소비 완료 상태를 재현하고 같은 실행의 사건으로 복구했다. 원본 DB를 썼다면 보존 근거를 구분했다. | 보존한 W6 직접 경로로 준비한 두 실제 확정 주문에 Outbox가 없는 상태를 확인. 같은 실행의 DB·broker·PG를 유지하여 W7 복구로 기존 사실의 Outbox만 추가. 미발행 활동 생성·기소비 활동 불변·두 번째 복구 불변 |
| W7-C08 | 준비와 복구의 PG 호출 수를 나눠 기록했으며 복구 중 PG 추가 호출·주문 재확정·예약 추가 소비는 없다. | 준비 종료 시 PG create 누적 C·외부 거래 T를 기록. 복구 중 create 증가 0·조회/callback 추가 호출 0·거래 수 T 유지. 주문·예약·재고·포인트 추가 효과 없음 |
| W7-C09 | PENDING·PUBLISHED·소비 완료를 구분하고 실패·backlog·재개 방법을 확인했다. | 유한 전송 대기·batch·재시도 간격, 오류/eventId·시도 수·PENDING 수·가장 오래 기다린 시각의 SQL/로그. 장애 회복 후 같은 행 재개, 늦은 callback의 상태 덮어쓰기 방지. consumer lag·오류·구매 활동 별도 확인 |
| W7-C10 | 미발행 사실을 삭제하지 않으며 단일 relay·보존·재시도의 한계를 설명한다. | 미발행·격리 행 자동 삭제 없음. Outbox·Kafka·consumer dedup 보존 관계, 단일 실행 제약·broker/consumer 복구 조건·영구 오류의 재개 경로 설명. 단일 broker·메모리 중첩 방지의 보장 한계 구분 |
| W7-C11 | 관련 회귀·lint·ArchUnit을 실행하고 자신이 만든 테스트 자원만 정리했다. | W5 결과 적용·예약·소유권·과거 POINT 및 W6 소비 멱등성·실패 정책 회귀, 양 앱 정적 검사 범위·건수·실패/오류/skip·종료 결과. deadline과 소유 자원 정리 |

판정은 **충족·부분 충족·미충족·확인 불가**로 구분한다. 충족에는 필수 코드와 실제 실행 근거가 필요하다. 일부 필수 경로나 증거가 남으면 부분 충족, 필수 동작 부재나 규칙 위반이 확인되면 미충족, 환경·자료 제약으로 판단할 수 없으면 확인 불가로 표시한다. W6 통과를 W7 통과로 이어받지 않는다. **기능·실험 결과와 제출용 기록·PR·기술 글의 준비 상태는 분리**해 설명한다.

## W6에서 유지하고 교체하는 경계

| 유지할 계약 | W7에서 바뀌는 책임 |
| --- | --- |
| 기존 PG 조회·검증과 confirm/callback/reconcile의 공통 결과 적용, 잠금·예약·소유권·과거 POINT 사실 | 최초 PG 성공 반영 TX에 Outbox insert 추가. 로컬 rollback이 PG 결제를 취소한 것으로 취급하지 않음 |
| OrderConfirmed v1의 stable eventId·orderId 문자열 key·최초 confirmedAt·저장 items, 중복 품목 정규화·민감정보 제외 | 확정 시 만들어 영속 저장한 key/payload를 relay가 그대로 전송. 현재 상품 재조회로 재구성하지 않음 |
| 기존 Kafka topic·구매 활동 consumer/group·DB 중복 방지·공동 TX·commit 뒤 ack | 주문 확정 후 직접 send를 Outbox 기록과 relay로 교체. 같은 주문 경로에서 두 발행 방식을 병행하지 않음 |
| W6 실제 DB·broker·로컬 PG·consumer 테스트 기반 | 실제 commit·두 중단 구간·relay 재생성·누락 복구를 같은 실행의 사실로 검증 |

기존 `DomainEvent` 공통 구조를 어떻게 활용할지는 구현 선택이다. 특정 listener 이름·Outbox 클래스·패키지·테이블명이나 테스트 파일명을 유일한 정답으로 정하지 않는다. 바뀐 계약과 실제 구현은 [전체 설계](../week2/commerce-erd-draft.md), [정책](../week2/commerce-policy-decisions.md), [TDD 계획](../week2/commerce-tdd-plan.md)에 연결한다.

## Outbox의 사실과 원자성

최소 모델에는 다음 의미가 있어야 한다. 이름과 SQL 타입은 설계에서 정한다.

| 의미 | 규칙 |
| --- | --- |
| eventId | W6와 같은 확정 사건 ID, DB 유일 제약 |
| orderId·eventType·schemaVersion | 원본 주문과 W6 이벤트 계약 연결 |
| messageKey·payload | 확정 당시 값으로 영속 저장, 생성 후 변경 금지 |
| status | 최초 PENDING, broker 성공 확인과 상태 저장 뒤 PUBLISHED |
| createdAt·publishedAt | Outbox 기록 시각과 발행 확인 저장 시각 구분. confirmedAt은 주문의 최초 사실 |
| attemptCount·nextAttemptAt·lastError | 전송 시도·지연·오류 관찰. 시도 횟수를 broker 실제 수신 횟수로 간주하지 않음 |

검증된 PG SUCCESS를 적용할 때 **기존 주문·시도·예약 변경과 Outbox를 같은 로컬 TX로 묶는다.** serialization 실패도 이 TX를 실패시킨다. 실제 SQL 이후 오류에서도 모두 rollback하는지 DB 재조회로 확인한다. `@Transactional` 이름만으로 원자성을 판단하지 않는다.

실패 기대값은 **기존 미완료 주문·시도·예약 보존과 새 Outbox 0**이다. 이미 존재하던 주문 행을 삭제하거나 PG SUCCESS를 실패로 되돌리는 것이 아니다. 같은 거래의 결과 조회·적용을 다시 수행해 최초 성공 반영을 끝내되 새 결제를 만들지 않는다.

이때의 PG 재조회는 **아직 로컬 확정이 완료되지 않은 TX 실패의 복구**다. 아래 W6 누락 복구는 **이미 확정된 주문의 전달 사실을 보충**하는 작업이므로 PG 조회·callback까지 추가 호출하지 않는다. 두 경로의 기대값을 혼동하지 않는다.

## Relay의 전송 확인과 재시도

단일 polling relay는 재시도 시각이 된 PENDING을 작은 묶음으로 안정된 순서에서 찾고, 저장된 사건으로 시도 정보를 남긴 뒤 DB TX 밖에서 전송한다. 조회·상태 변경 TX와 외부 전송 대기를 분리하며 상태·nextAttemptAt·정렬에 맞는 인덱스를 검토한다.

| 실제 결과 | 저장 상태와 해석 |
| --- | --- |
| send 호출이 future를 반환 | 아직 발행 성공 증거가 아님 |
| future 성공 metadata + 상태 TX 성공 | PUBLISHED·publishedAt. 소비 완료는 별도 |
| 전송 실패 또는 로컬 대기 timeout | 같은 행·eventId·payload를 재시도 대상으로 유지, 다음 시각·오류 기록. broker 수신 0회라고 단정하지 않음 |
| broker 확인 뒤 중단 또는 상태 TX 실패 | PENDING이 남아 같은 사건을 다시 보낼 수 있음 |
| timeout 후 늦은 성공 callback | 새로운 시도의 상태를 임의로 덮어쓰지 않음. 상태 변경 책임은 relay의 한 경계에 둠 |

한 프로세스에서 scheduler·수동 실행이 겹치지 않게 하고 다른 인스턴스의 relay를 동시에 켜지 않는 로컬 설정을 정한다. 메모리 flag는 다중 프로세스의 분산 잠금 증거가 아니다. 무제한 future 병렬 생성·tick 중첩으로 확장하지 않는다. `acks`는 broker 확인을 받을 수 있는 값을 선택하고 단일 broker 실험으로 복제 장애 내구성을 주장하지 않는다.

대기 제한·한 회차 처리 개수·재시도 간격은 구현 전에 정한다. 테스트의 짧은 대기를 운영 값으로 그대로 고정하지 않는다. 반복 영구 오류는 원인과 eventId를 노출한다. **BLOCKED는 선택 사항**이며 사용한다면 미해결 행을 보존하고 원인 제거 뒤 같은 행을 재개해야 한다. SQL·구조화 로그로 관찰할 수 있으며 새 모니터링 서버는 필수가 아니다. lastError와 로그에는 카드·비밀·불필요한 전체 payload를 남기지 않는다. 대상 eventId의 로컬 운영 진입점 또는 테스트 도구로 재개할 수 있고 공개 관리자 API로 노출하지 않는다.

PENDING·PUBLISHED·구매 활동 완료는 서로 다른 상태다. PUBLISHED인데 활동이 없으면 consumer의 lag·오류·실제 DB를 확인한다. 이번에는 미발행·격리 행을 자동 삭제하지 않으며 Outbox 보존, Kafka retention, consumer dedup 기록 보존의 관계를 설명한다.

## 두 중단 구간과 실제 E2E

| 중단 위치 | 중단 직후 확인 | 같은 상태를 이어받아 재시작한 결과 |
| --- | --- | --- |
| 확정 TX commit 뒤 relay 실행 전 | CONFIRMED·예약 소비·Outbox PENDING, 구매 활동 없음 | 새 relay가 저장 행에서 찾아 실제 broker·consumer를 통해 활동 생성 |
| 실제 broker 확인 뒤 PUBLISHED 저장 전 | 첫 send 성공 metadata·broker record 존재, 같은 Outbox PENDING | 새 relay의 재발행도 같은 eventId/key/payload. 별도 record·수신이 생겨도 품목별 구매 효과 불변, 최종 PUBLISHED |

hook은 앞 단계가 완료된 뒤 다음 단계를 못 가게 둔다. broker 확인 전 예외로 두 번째 중단을 대신하지 않는다. 재시작은 relay 객체·실행 흐름을 새로 만들고 DB·Kafka·eventId는 유지한다. 중단 후 테스트가 상태를 PENDING으로 덮어쓰거나 행을 삭제해 조건을 꾸미지 않는다. 통제한 예외·객체 재생성을 OS 강제 종료 실험이라고 표현하지 않는다.

한 E2E는 **기존 주문 API·로컬 PG·실제 MySQL/Kafka·실제 구매 활동 consumer**를 연결한다. 품목이 상품 7×2·상품 8×1이면 같은 주문·외부 거래 각각 1, 같은 eventId Outbox 1행, 완료 기록 1건, 활동 2행과 수량 2·1이 최종 기대값이다. 첫 발행과 재발행의 **실제 broker record와 consumer 수신을 각각 2회 이상** 확인한다. send 호출 수만 세지 않는다. 재고 예약 추가 소비·반환과 PG 주문 포인트 추가 차감은 없어야 한다.

W6는 같은 topic·partition·offset의 **재전달**, W7은 같은 eventId가 별도 record가 되는 **재발행**을 검증한다. producer idempotence나 W6 실험만으로 W7 중단 E2E를 대체하지 않는다.

## W6 누락과 정상 소비 두 사례의 복구

[W6 재현 가이드](../week6/fixed-clock-w7-reproduction.md)의 구현 revision `dd86f49a9edf7a7ccdfc672be3f708f0f4a3ddd1`과 고정 입력·준비 코드를 출발점으로 삼는다. 새 격리 실행은 과거 원본 행의 복원이 아니다. 같은 실행의 준비와 복구 사이에서 실제 생성된 사건 사실을 보존한다.

1. 소유한 MySQL·Kafka·로컬 PG 환경에서 **W6 실제 직접 발행 경로**로 두 주문을 확정한다. 한 주문은 commit 후 send 직전에 중단하고, 다른 주문은 실제 소비까지 끝낸다. 두 주문 모두 CONFIRMED·예약 소비, Outbox 없음, 누락 주문만 활동 0을 새 DB 조회로 확인한다. 사례 생성 순서 자체를 합격 조건으로 삼지 않는다.
2. 이번 실행의 orderId·eventId·confirmedAt·items·PG 거래 식별자와 PG create 누적 C·외부 거래 T를 기록한다. 준비 중 생성한 거래 수를 복구의 추가 호출로 혼동하지 않는다.
3. 직접 발행을 중단하고 **같은 DB**에 W7 Outbox 구조·복구·단일 relay를 연결한다. 준비→복구 중 DB·broker·PG 초기화나 `@AfterEach` 정리를 하지 않는다. SUCCESS 직접 insert, W7 Outbox 삭제, 운영용 우회 스위치로 W6 상태를 꾸미지 않는다.
4. 대상 두 주문의 저장 사실에서 기존 eventId·confirmedAt·품목 그대로 없는 Outbox만 추가한다. 이미 있으면 내용 일치를 확인한다. 원본 사실 부족·충돌은 추측하지 않고 오류로 드러낸다.
5. relay·기존 consumer를 실행해 누락 활동 생성과 이미 소비한 활동의 PK·수량·사실 보존을 확인한다. 두 번째 복구도 Outbox·구매 효과를 늘리지 않는다. 복구 중 PG create 증가 0·PG 조회/callback 추가 호출 0·거래 T 유지·주문 재확정과 예약 추가 소비 0을 확인한 뒤 자원을 정리한다.

두 주문을 한 번씩 시작하는 최소 준비라면 C=2, T=2이지만 핵심은 **복구 직전의 실제 기준값과 이후 증가 0**이다. 원본 DB를 실제로 보존했다면 준비 재현을 생략할 수 있으며 보존 근거와 사건 사실을 먼저 검증한다. 이 경우에만 원본 사건 복구라고 부른다. 어느 방식도 과거 POINT 주문 전체를 자동 소급하지 않는다.

## 실행과 제출

관련 테스트 이름은 실제 구현에 맞게 선택한다. 원문의 `OutboxAtomicityTest`, `OutboxDeliveryE2ETest`는 예시다. Java 최종 검사 예시는 다음과 같다.

```shell
./gradlew :apps:commerce-api:check :apps:commerce-streamer:check
```

실제 실행 범위·테스트 수·실패/오류/skip·종료 결과를 기록한다. 테스트 0건·skip·연결 실패를 통과로 세지 않고 양 앱의 ArchUnit·Checkstyle 범위를 구분한다. 영향 없는 모든 계층에서 같은 시나리오를 반복할 필요는 없다.

테스트는 고유 topic/group·합성 주문과 실제 producer/consumer/admin의 같은 broker 주소를 사용한다. 실제 listener factory·parser·handler를 연결하고 자동 rollback 안에 전체 과정을 숨기지 않는다. 두 앱의 전체 component scan 충돌을 피하며 제품 runtime에 API→streamer 의존성을 추가하지 않는다. broker 준비·consumer 배정·결과 대기에 deadline과 실패 상태 출력을 두고 자신이 만든 자원만 정리한다.

AI와 각 중단 위치의 DB·broker·활동 결과를 먼저 예측하고 반례를 검토한다. 특히 PUBLISHED 선저장 후 전송 전 중단은 미발행을 완료로 숨긴다. 이 검토는 위 원자성·전달 항목의 증거이며 별도 12번째 항목이 아니다.

제출물은 Outbox·relay 코드, 로컬 원자성·중단·E2E 테스트, 기존 누락 연결 방법, PR·기술 글이다. PR에는 같은 eventId의 주문→Outbox→broker→구매 활동 결과, 단일 relay 제약과 미해결 오류 재개 방법을 짧게 적는다. **구현·검증 완료와 제출 기록·실제 제출 완료를 따로 보고**한다.

## 필수 범위를 넓히지 않는 기준

- 이번 필수 구현은 단일 polling relay다. 다중 relay·분산 claim/lease·CDC 플랫폼·범용 saga·새 결제수단·새 주문 도메인은 필수가 아니다. polling과 CDC의 비용·추가 책임은 설명할 수 있어야 한다.
- 랭킹 API·운영 DLQ 제품·모니터링 서버·공개 재처리 관리자 API를 완료 조건으로 추가하지 않는다.
- BLOCKED 상태·구체 재시도 수치·물리 테이블/클래스 이름·scheduler 또는 수동 실행 방식은 선택할 수 있다. 선택과 무관하게 단일 실행·재개·원자성·불변 사실은 지킨다.
- OS 강제 종료·다중 broker 장애·업무 전체 순서 보장은 이번 필수 실험으로 확대하지 않는다. at-least-once 발행과 consumer의 DB 중복 방지를 end-to-end exactly-once로 표현하지 않는다.
- W6의 재현 revision은 준비 코드의 근거다. 문서 갱신 자체를 W7 구현·실험 성공으로 판정하지 않는다.
