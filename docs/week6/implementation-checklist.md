# W6 구현·검증 체크리스트

[과제 원문](assignment-original.md) · [학습 원문](learning-original.md) · [11개 채점 기준](grading-criteria.md) · [전체 설계](../week2/commerce-erd-draft.md) · [이전 완료 기록](../week2/commerce-completion-checklist.md)

## 점검 기준과 현재 결론

**2026-10-29 선행 발행 증분:** Q01 결정적 eventId·Q02 after-commit listener에 이어 사용자가 Q05의 Kafka 완료를 기다리지 않는 callback 관찰을 선택했다. 공통 최초 확정 hook·발행 포트·listener·Kafka adapter를 연결했다. **09:48 KST, 신규 발행 23개를 포함한 관련 47개 스위트·862개 통과**, 실패·오류·skip 0, API ArchUnit·Checkstyle main/test 위반 0, BUILD SUCCESSFUL·1분 52초·종료 0이다. 실제 DB·PG HTTP·Kafka wire/metadata·누락/timeout·자원 종료 증거는 [발행 TDD 기록](order-confirmed-publication-tdd-log.md)을 따른다. 첫 모델의 신규 40개·관련 183개·API Checkstyle 통과는 [선행 기록](order-confirmed-event-tdd-log.md)으로 보존하며 서로 합산하지 않는다.

**2026-10-27 초기 정적 점검:** 기준 HEAD는 `6e686d824d979ad16a7a2baa70afbeee2c3ce460`이다. 최초 점검 시작 시 worktree는 깨끗했고 당시 변경은 원문·체크 기준·문서 연결뿐이었다. 이 commit을 W6 구현 완료나 W7 재현용 실행 revision으로 사용하지 않는다. 아래 선행 검사는 그 위의 미커밋 작업 트리에서 실행했으며, 이후 구현 revision 연결은 2026-10-30 C09 기록으로 구분한다.

**2026-10-29 후속 소비 증분:** Q03의 두 테이블·필드 비교, Q04의 실패 중단·같은 group 재시작을 선택했다. streamer 수신 검증·공동 DB TX·중복/충돌·전용 listener/ack와 DDL/validate를 연결했다. 실제 검사 명령·결과·범위는 [구매 활동 소비 TDD 기록](purchase-activity-consumer-tdd-log.md)을 따른다. 당시 후속이었던 DB commit 뒤 offset 공백(C05)·consumer 배정(C04)·정상 PG→streamer 경로는 아래 증분에서 검증했다. 직접 발행 누락의 소비 DB 대조는 아래 C08 증분에서 검증했고, 당시 후속이었던 W7 두 사례의 정식 인계는 아래 C09 기록에 연결한다. 사용자 DB·기존 broker·다른 실행의 topic/group·볼륨은 사용하거나 삭제하지 않는다.

**2026-10-29 offset 공백 재전달 증분:** 실제 DB commit 뒤 `n+1`을 commit하지 않고 첫 consumer를 종료했다. 같은 broker·topic·group·DB에서 새 consumer가 seek/reset 없이 동일 record를 다시 받아 `DUPLICATE`로 처리했고, 완료 1건·활동 2건의 PK·전체 필드가 유지됐다. 실제 committed offset은 `n → n → n+1`이다. 생산 코드 변경 없이 검증을 추가했으며 실행 명령·결과·한계는 [offset 공백 재전달 TDD 기록](purchase-activity-offset-redelivery-tdd-log.md)을 따른다.

**2026-10-29 Kafka 배정 증분:** DB 없이 전용 Kafka의 고유 probe topic/group에서 consumer 1→2→3의 실제 배정을 확인했다. broker의 STABLE/member 배정과 계속 poll하는 worker의 배정을 함께 확인한 뒤 각 단계에서 두 partition의 probe 수신·commit을 검증했다. 별도 key 실험은 producer가 고른 실제 metadata를 비교한다. 명령·검사 범위·배정표·한계는 [Kafka 배정·key 매핑 TDD 기록](kafka-group-assignment-tdd-log.md)을 따른다. 운영 consumer 설정·업무 이벤트·사용자 DB·기존 topic/group은 변경하지 않았다.

**2026-10-29 정상 전체 흐름 증분:** 실제 주문 생성·confirm HTTP → 로컬 PG 성공 → 주문 commit → Kafka → 별도 production streamer JVM의 listener → 별도 구매 활동 DB·ack를 한 경로로 검증했다. 같은 confirm 재요청에서 추가 PG 생성·Kafka record·재고 차감·구매 활동 변경이 없었다. API와 streamer의 classpath/DB를 분리하는 테스트 실행 설정만 추가했으며 운영 로직은 변경하지 않았다. 실제 명령·사건 값·검사 범위는 [정상 전체 흐름 TDD 기록](normal-pg-purchase-activity-e2e-tdd-log.md)을 따른다. 당시 후속이었던 C08의 소비 DB 대조는 다음 증분에서 검증했다. 당시 남았던 고정 Clock·재현 준비와 실제 W6 구현 commit 해시 연결은 아래 증분으로 연결했고, 최종 대조는 후속이다.

**2026-10-29 commit 뒤 발행 누락 증분:** 정상 주문의 구매 활동 저장·ack를 먼저 확인한 뒤, 별도 주문의 실제 확정 commit을 독립 DB 연결로 확인하고 Kafka send 직전에 실패를 주입했다. 주문·예약·재고·포인트·최초 확정 시각은 보존됐고 broker endOffsets와 정상 구매 활동은 불변이며, 누락 대상의 record·완료 기록·구매 활동은 0건이었다. 실제 streamer JVM이 살아 있는 상태에서 대조했으며, 해당 주문의 confirm·callback·reconcile 후에도 추가 PG 생성·발행과 저장 결과 변경이 없었다. 테스트만 확장했고 실제 실행 결과·사건 값·검사 범위는 [발행 누락 전체 흐름 TDD 기록](pre-send-failure-e2e-tdd-log.md)에 연결한다. C08을 검증한 당시 기록이며, 이후 고정 Clock·재현 준비는 다음 증분으로 구분한다.

**2026-10-29 고정 Clock·W7 재현 준비 증분:** `PaymentConfirmationConfiguration`의 전용 `orderPaymentClock`을 주문 결제 서비스에 주입했다. 운영 기본값은 `Clock.systemUTC()`이며 테스트는 `2026-10-29T00:00:00.123456Z`로 고정한다. 정상 소비·send 직전 누락 두 사례의 발송 시작·최초 확정 시각을 검증하고 기존 broker/DB·재요청 기대값을 유지했다. PG 대기 예산·회로·단조 시간, JPA 감사 시각·로그·Kafka 시계와 TX·락은 변경하지 않았다. 재현 코드·명령·실제 사건 값·검사 범위·자원 정리는 [고정 Clock·W7 재현 가이드](fixed-clock-w7-reproduction.md)를 따른다. 당시 C09에서 남은 것은 실행 코드를 담은 실제 W6 commit 해시 연결이었으며 사용자 요청에 따라 커밋하지 않았다. 이후 연결은 아래 C09 기록을 따른다. C11 최종 대조와 제출은 별도 후속이다.

**2026-10-30 공통 발행 리팩터링:** 사용자 요청에 따라 순수 `DomainEvent`·`DomainEventPublisher`, 공통 `DomainEventListener`, `KafkaDomainEventPublisher`·`DomainEventKafkaConfiguration`·명시적인 타입별 `KafkaEventRoute`로 발행 책임을 정리했다. 외부 발행에 참여한 이벤트만 공통 AFTER_COMMIT 경로로 넘기며 topic/enabled는 infrastructure가 등록한다. 주문 최초 확정·실패 격리·wire·기존 설정·고정 Clock과 소비 DB/ack 계약은 유지한다. 두 번째 합성 이벤트로 확장을 검증했으며 새 업무 기능은 추가하지 않았다. **15:17 KST 관련 15개 스위트·359개(API 306·streamer 53), 실패·오류·skip 0, BUILD SUCCESSFUL·58초·종료 0**이었다. 양 앱 ArchUnit을 통과했고 Checkstyle 보고서 4개의 위반은 0이다(API main/test 실행, streamer main/test UP-TO-DATE). 실행 전후 Docker 자원 ID가 같아 새 테스트 자원 잔존이 없었으며 커밋하지 않았다. 이번 Red/Green·실제 회귀·검사 범위는 [공통 도메인 이벤트 발행 TDD 기록](common-domain-event-publication-tdd-log.md)을 따르며 아래 선행 실험 건수를 이번 실행으로 간주하지 않는다.

**2026-10-30 C09 revision 연결:** 이미 존재하는 W6 구현 commit `dd86f49a9edf7a7ccdfc672be3f708f0f4a3ddd1`을 재현 revision으로 연결했다. 실행 HEAD `3298b443b39357fa7741d720d3c9efc559af37eb`와 구현·테스트·실행 설정이 같음을 확인하고, **16:14 KST 두 사례 E2E 1개 통과, 실패·오류·skip 0, BUILD SUCCESSFUL·36초·종료 0**을 확인했다. 새 사건 사실·명령·자원 정리는 [C09 재검증 기록](fixed-clock-w7-reproduction.md#2026-10-30-c09-revision-연결과-재검증)을 따른다. 기존 코드 commit을 재사용하며 이번 변경은 문서뿐이다. 선행 회귀·정적 검사 건수를 이번 실행으로 재사용하지 않으며 C11 최종 대조·PR·제출은 별도다.

## 공식 항목별 현재 상태

아래 ID는 [채점 기준](grading-criteria.md#공식-11개-항목과-확인-증거)와 같다. `미착수/미실행`은 작업 추적 상태이며 최종 점수·판정이 아니다. 통과로 바꿀 때는 테스트 이름뿐 아니라 명령·실행 시점·실제 관찰값·관련 revision을 연결한다.

| ID | 확인할 기능·실험 | 현재 상태 | 다음에 남길 증거 |
| --- | --- | --- | --- |
| W6-C01 | PG 최초 확정 → Kafka → 구매 활동 | 정상 confirm HTTP부터 별도 streamer JVM/DB까지 실제 검증 완료 / [실행 기록](normal-pg-purchase-activity-e2e-tdd-log.md) | 실제 PG·주문 commit·Kafka 위치와 저장 품목/수량/시각·streamer committed offset 일치, 동일 confirm 재요청 불변 |
| W6-C02 | 대기·실패·rollback·중복 적용의 미발행 | DB commit·rollback·중복·경합 및 실제 HTTP 재요청의 추가 발행/PG 생성 0 검증 | 상태별 발행 0, 중복 callback/reconcile의 추가 효과 0 |
| W6-C03 | 과거 구매 사실·민감정보 제외 | 생산자 wire 검증에 소비 정규화 필드·정확한 시각/ID 저장 추가 / 실행 범위는 소비 기록 참조 | 실제 serializer·저장 값 일치·민감정보 배제·삭제 후 소비 사실 보존 |
| W6-C04 | partition 2개·consumer 1→2→3 | 실제 broker에서 검증 완료 / [실행 기록](kafka-group-assignment-tdd-log.md) | 안정된 broker/worker assignment 일치, 각 단계 두 partition 수신·commit, 같은 key metadata |
| W6-C05 | DB 성공 후 offset 공백 재전달 | 실제 broker·MySQL에서 검증 완료 / [실행 기록](purchase-activity-offset-redelivery-tdd-log.md) | 동일 record 전달 2회, APPLIED→DUPLICATE, PK·전체 필드 불변, committed offset n → n → n+1 |
| W6-C06 | 완료 기록·모든 품목 원자성/충돌 | 두 테이블·공동 TX·유일 제약 경합·다른 사실 충돌·실제 SQL 뒤 rollback 구현 / 소비 기록 참조 | 유일 제약·동시 중복·다른 payload 충돌·중간 SQL 실패 rollback |
| W6-C07 | 실제 listener 실패·ack·재시작 | 전용 Spring listener·중단형 handler·같은 group 재시작 연결 / 실제 실행 결과는 소비 기록 참조 | DB rollback·offset 정지·오류 노출·같은 group 재시작 후 성공 |
| W6-C08 | commit 뒤 직접 발행 누락 | 독립 연결의 commit 확인·send 직전 실패·실제 broker/streamer DB 대조 검증 완료 / [실행 기록](pre-send-failure-e2e-tdd-log.md) | 정상 소비 양성 대조와 streamer 생존, 확정/예약·재고/포인트 보존, 해당 record/완료/활동 0, 세 HTTP 재요청의 추가 PG 생성/발행 0 |
| W6-C09 | W7 인계 두 사례와 재현 정보 | 완료 — 기존 W6 구현 `dd86f49`·동일 코드 재실행·고정 Clock·두 사례·정리를 [C09 기록](fixed-clock-w7-reproduction.md#2026-10-30-c09-revision-연결과-재검증)에 연결 | 보존 완료: 미발행1·소비완료1의 준비 코드·명령·실제 사건 값·실행 revision·정리 |
| W6-C10 | 실효 설정·실제 주소·보장 한계 | API producer에 streamer 전용 record/auto commit false/ack·중단형 handler·동일 bootstrap 설정 추가 | producer/consumer/admin 같은 broker, 실효 auto commit·ack/error handler·acks 검증 |
| W6-C11 | 관련 회귀·lint·ArchUnit·정리 | 선행 API/streamer 회귀·배정·[고정 Clock 검사](fixed-clock-w7-reproduction.md)는 보존 / 최신 공통 발행 구조 변경의 검사 범위·자원 종료는 [공통 발행 TDD 기록](common-domain-event-publication-tdd-log.md) 참조. 최종 대조는 후속 | 이후 변경 범위의 실행 수·실패/skip·종료 결과·검사 적용 범위·자원 종료 |

## 현재 프로젝트에서 이어 쓸 부분과 바꿀 책임

다음은 초기 점검에서 이어 쓸 코드와 이번에 연결한 책임이다. 개별 실제 실행 증거는 위 기록과 구분한다.

| 영역 | 현재 확인한 사실·근거 | W6에서 확인·변경할 책임 |
| --- | --- | --- |
| PG 공통 결과 적용 | [OrderPaymentStockService](../../apps/commerce-api/src/main/java/com/loopers/application/order/OrderPaymentStockService.java)의 observe → APPLIED → applyStockEffect → confirmPgPayment | 최초 성공 전이에서 이벤트 준비·실제 commit 뒤 listener 발행을 연결. 기존 TX/락과 공통 결과 경계 유지 |
| 주문·예약·최초 시각 | [Order](../../apps/commerce-api/src/main/java/com/loopers/domain/order/Order.java)의 confirmPgPayment가 confirmedAt·CONSUMED를 저장하고 중복이면 false 반환 | 저장된 시각·품목으로 payload 구성. 메서드의 true는 객체 변경이지 commit 완료가 아님 |
| 품목 스냅샷 | [OrderItem](../../apps/commerce-api/src/main/java/com/loopers/domain/order/OrderItem.java)에 productId·quantity·당시 품목 정보 존재 | 필요한 productId/quantity만 사용. 현재 상품 조회·카드·HMAC 지문을 이벤트에 추가하지 않음 |
| API 생산자 연결 | [API Gradle](../../apps/commerce-api/build.gradle.kts)에 `spring-kafka` 직접 의존. 공통 `DomainEventPublisher`·listener와 infrastructure의 typed route·producer·adapter | topic/enabled는 명시적인 타입별 route로 등록. domain에는 Kafka/topic 의존을 넣지 않고 기존 주문 설정·wire와 streamer batch 설정 유지 |
| streamer 앱 | 독립 수신 모델·parser·handler/별도 TX 서비스·두 Entity/JPA·수동 DDL 추가 | demo와 분리된 업무 listener. demo의 시작 설정을 제어해 격리 테스트에서는 끄며 기존 업무와 사용자 DB를 변경하지 않음 |
| 공통 listener factory | [KafkaConfig](../../modules/kafka/src/main/java/com/loopers/confg/kafka/KafkaConfig.java)는 batch=true·MANUAL·concurrency=3 | 전용 record factory·concurrency1·MANUAL_IMMEDIATE·중단형 error handler를 추가하고 기존 공유 factory는 유지 |
| 역직렬화·auto commit | [kafka.yml](../../modules/kafka/src/main/resources/kafka.yml)에 consumer value-serializer 및 properties의 enable-auto-commit 표기 | 전용 factory가 String/ByteArrayDeserializer와 실제 enable.auto.commit=false를 명시. 공유 YAML 오기를 성공 근거로 사용하지 않음 |
| broker 주소 | 기존 공유 YAML의 local/test bootstrap과 admin override가 다름. API 전용 설정은 이 YAML을 import하지 않음 | 각 앱 테스트의 producer/admin/consumer에 같은 소유 fixture 주소 주입. streamer의 마지막 purchase-activity.yml에서 admin bootstrap을 동일하게 설정 |
| 작은 테스트의 fetch | KafkaConfig의 fetch.min.bytes=1MiB·fetch.max.wait.ms=5000 | 전용 소비 factory에 fetch.min.bytes1·fetch.max.wait100ms·poll100ms를 선택. 무한 대기/긴 sleep 금지 |
| Kafka 테스트 기반 | API에 Testcontainers Kafka 의존성과 `PgOrderEventKafkaIntegrationTest` 추가 | 자신의 `apache/kafka:3.8.1`·고유 2-partition topic/group·유한 poll/close. 공식 재전달 실험 C05와 DB 없는 probe의 1→2→3 배정 C04 검증 완료 |
| MySQL 테스트 기반 | streamer 전용 Testcontainers MySQL·수동 DDL 적용·JPA validate | 재시작에도 완료 기록 보존. shared fixture 자동 시작에 의존하지 않고 사용자 DB 사용 금지 |
| 정적 검사 범위 | 루트 Checkstyle과 API ArchUnit을 유지하고 streamer 자체 ArchitectureTest·의존성 추가 | 각 앱의 실제 검사 실행 범위를 별도로 기록. API 검사 결과만으로 streamer 통과를 주장하지 않음 |

## 구현 전에 구체화할 선택

과제가 고정한 사실은 [이벤트 계약](grading-criteria.md#과제로-고정된-이벤트-계약)을 따른다. **2026-10-29 Q01~05는 사용자 선택 완료**이며 Q06의 생산자/소비자 fixture는 격리 TDD 방식으로 구체화했다. Q06의 DB commit 뒤 offset 공백·배정·정상 PG→streamer 전체 경로·직접 발행 누락의 소비 DB 대조는 실제 검증했다. 고정 Clock·W7 두 사례의 재현 코드/명령·실제 사건 값에 기존 W6 구현 commit과 동일 코드의 재실행 결과를 연결해 [C09 인계](fixed-clock-w7-reproduction.md#2026-10-30-c09-revision-연결과-재검증)를 완료했다. [선택 이유와 남은 항목](../week2/commerce-policy-decisions.md#w6-이벤트-전달의-선택과-남은-결정)을 구분하며 원문의 예시를 사용자 승인으로 취급하지 않는다.

| ID | 결정할 것 | 비교할 대안·영향 |
| --- | --- | --- |
| W6-Q01 · 선택 완료 | stable eventId 생성 방식 | `order-confirmed:v1:{orderId}`로 유도. 별도 최초 UUID 저장 대안은 채택하지 않음. 같은 주문 DB 식별 범위, 추가 컬럼 없음 |
| W6-Q02 · 선택 완료 | 실제 commit 뒤 발행 연결 | after-commit listener 선택. 공통 최초 전이에서 불변 payload를 준비하고 실제 commit 후 발행. 2026-10-30 요청으로 외부 발행용 DomainEvent·공통 listener/publisher·명시적 route로 일반화·검증했으며 commit/실패 경계는 유지. 실행 범위는 각 발행 TDD 기록 참조 |
| W6-Q03 · 선택 완료 | 완료 기록·구매 활동 저장과 사실 비교 | 두 테이블·필요 필드 비교, JSON 사본 없음. 완료 유일 제약 경합은 rollback 뒤 별도 TX에서 전체 사실 비교. 정확한 ID·시각 보존 |
| W6-Q04 · 선택 완료 | listener factory·ack·실패 정책 | 전용 record·concurrency1·MANUAL_IMMEDIATE·auto commit false. 실패 시 중단·원인 해결 뒤 같은 group 재시작. local validate로 DB 완료 기록 보존 |
| W6-Q05 · 선택 완료 | 발행 실패 노출·producer 설정 | 완료 미대기 callback, metadata/실패 종류 로그. max.block1초/request3초/delivery10초/retries3/acks all/idempotence/in-flight5 선택. 세부 이유·한계는 정책 참조 |
| W6-Q06 · 구체화·인계 완료 | 실제 broker 실험·재현 증거 보관 | 생산자·소비 실패/재시작·offset 공백·배정·정상 PG→streamer 전체 경로·직접 발행 누락의 소비 DB 대조를 격리 검증. 고정 Clock·두 사례·정리와 기존 W6 구현 `dd86f49`·동일 코드 재실행을 [C09 기록](fixed-clock-w7-reproduction.md#2026-10-30-c09-revision-연결과-재검증)에 연결 |

실제 설계는 별도의 두 번째 전체 설계 문서를 만들지 않고 [기존 전체 설계](../week2/commerce-erd-draft.md)에 W6 목표를 확장했다. 선택 이유와 버린 대안은 [정책 문서](../week2/commerce-policy-decisions.md), 상세 Red/Green 기대값은 [TDD 계획](../week2/commerce-tdd-plan.md)에 연결했다. 이 체크리스트 자체를 최종 설계나 실행 증거의 대체물로 쓰지 않는다.

## 제안하는 진행 순서

아래 6단계는 작업 순서 제안이지 공식 체크 항목 수가 아니다. 각 단계에서 바꿀 파일·책임·실패 기대값을 먼저 정하고 작은 TDD 증분으로 진행한다.

| 단계 | 집중할 일 | 먼저 확인할 테스트 | 연결 |
| --- | --- | --- | --- |
| 1 | 이벤트 계약·저장 구조·TX 경계 선택, 기존 설계 확장 | key/orderId 일치, 고정 ID·최초 시각, 품목 합산/순서 독립, 잘못된 버전/수량·민감정보 제외 | C01~03·06, Q01~03 |
| 2 | 실제 Kafka fixture·전용 설정 기반 | 실효 serializer/deserializer·auto commit·bootstrap, bounded broker 준비/종료 | C10~11, Q04~06 |
| 3 | PG 최초 확정 후 직접 발행 | 실제 commit 후 발행, rollback/대기/실패/중복 미발행, send 실패에도 기존 확정 보존 | C01~02·08 |
| 4 | streamer 구매 활동·중복 처리·listener 연결 | 같은 사실 no-op, 같은 eventId의 다른 주문·내용/같은 orderId의 다른 eventId·사실은 충돌, 동시 유일 제약, 모든 품목 공동 rollback·DB commit 후 ack | C01·03·06~07 |
| 5 | 실제 전달·실패 실험과 두 사례 기록 | 1→2→3 assignment, DB commit 뒤 실제 재전달, Spring listener 실패/재시작, 미발행1·소비완료1의 재현 정보 | C04~05·07~10 |
| 6 | 영향받는 회귀와 제출 대조 | PG/POINT·예약·권한·삭제 관련 회귀, 두 앱 lint·계층 검사, 자원 정리·11개 증거 확인 | C11·제출 |

단위 handler 중복 테스트, 실제 broker 재전달 실험, 실제 Spring listener 실패 실험은 서로 대체하지 않는다. 기존 모든 장시간 성능 실험을 무조건 반복하지 않고 영향받는 회귀를 선정하되, 이번 과제의 필수 Kafka/DB 실험을 생략하지 않는다.

원문이 요청한 AI 반례 검토는 “DB 저장 뒤 commit 전 중단”, “handler가 예외를 삼킴”, “같은 ID로 다른 수량 수신”을 대상으로 한다. 채택한 보호 규칙과 이를 확인한 코드·테스트를 C05~07 및 관련 실행 기록에 연결한다.

## 제출·이번 문서 작업의 범위

- [x] 이벤트/트랜잭션 설계와 선택 이유 갱신 — Q01~05·발행/소비·정상/누락 경로·고정 Clock·재현 가이드와 Q06 실제 W6 commit·재실행 연결
- [ ] 주문 발행·구매 활동 소비 코드 및 관련 테스트
- [ ] 11개 공식 항목의 실제 실행 증거 연결
- [x] W7 인계용 두 사례의 재현 코드·사건 사실·사용 revision 보존 — 고정 Clock·재현 코드/명령·기존 W6 구현 `dd86f49`·동일 코드 재실행의 실제 사건 값·정리를 [C09 기록](fixed-clock-w7-reproduction.md#2026-10-30-c09-revision-연결과-재검증)에 연결
- [ ] 기술 글 작성
- [ ] PR에 활동 결과·배정표·offset·누락 구간·실험 한계 기록
- [ ] 커밋·푸시·PR·과제 제출 완료 여부 확인

초기 원문·기준/점검표와 첫 모델 증거는 보존한다. streamer 수신·공동 저장·중복/충돌·listener/ack와 전용 설정에 이어, DB commit 뒤 offset 공백의 실제 재전달과 별도 probe의 Kafka 배정·key 매핑을 검증했다. 각 증분 기록의 실제 결과·건수·한계를 구분하며 과거 수를 합산하거나 남은 실험까지 통과로 확대하지 않는다. 원문과 기존 API 스키마는 유지하고 streamer 전용 DDL만 제공했으며 사용자 DB에는 적용하지 않았다.
