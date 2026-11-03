# W6 DB commit 뒤 offset 공백 재전달 검증 기록

## 범위와 목표

2026-10-29 사용자가 **DB 저장은 성공했지만 Kafka에 완료를 알리기 전에 끊겼을 때, 같은 메시지가 다시 와도 구매 활동이 한 번만 저장되는지** TDD 검증을 요청했다. [과제 원문](assignment-original.md)의 실제 재전달 실험과 [W6-C05](grading-criteria.md#공식-11개-항목과-확인-증거)를 대상으로 한다.

기존 [구매 활동 소비 기록](purchase-activity-consumer-tdd-log.md)은 DB 저장 실패 시 전체 rollback과 실제 Spring listener 중단·재시작(C07)을 검증했다. 이번에는 **DB commit 성공 후 offset 미커밋** 경계를 별도로 확인한다. 두 실험은 서로 대체하지 않는다.

기준 HEAD는 `6e686d824d979ad16a7a2baa70afbeee2c3ce460`이며 검사는 기존 미커밋 작업 트리 위에서 실행했다. 신규 파일은 [OrderActivityOffsetRedeliveryIntegrationTest](../../apps/commerce-streamer/src/test/java/com/loopers/interfaces/consumer/OrderActivityOffsetRedeliveryIntegrationTest.java)다. 운영 코드·DB 스키마·기존 주문/결제 트랜잭션·잠금·소비 계약을 변경하지 않았다. 사용자 DB에 접속하거나 데이터를 수정하지 않았다.

## 먼저 정한 기대값

| 확인 대상 | 기대값과 검증 방법 |
| --- | --- |
| 실제 메시지 위치 | producer의 send metadata에서 topic·partition·offset `n`을 얻는다. 숫자를 미리 정답으로 고정하지 않는다. |
| 최초 완료 위치 | 첫 consumer가 실제 group에 배정된 뒤 `n`을 commit하고 `n`부터 읽도록 준비한다. 초기 배정 callback에서 pause하여 준비 중 poll이 target을 읽고 버리지 않게 한다. |
| 최초 처리 | 실제 parser와 Spring handler가 APPLIED를 반환한다. 새로운 물리 JDBC 연결로 완료 1건·활동 2건, 상품 7 수량 2·상품 8 수량 1, 최초 시각을 확인한다. |
| DB commit 뒤 중단 | `n+1` commit 요청 없이 첫 consumer를 닫는다. close 전후 모두 AdminClient가 읽은 실제 committed offset은 `n`이다. |
| 재전달 | broker·topic·group·DB를 그대로 두고 consumer만 새로 만든다. 새 consumer에는 seek·reset·재발행을 하지 않는다. topic·partition·offset·key·payload가 모두 첫 record와 같다. |
| 중복 효과 | 실제 handler는 DUPLICATE를 반환한다. 두 테이블의 PK를 포함한 전체 행을 비교하여 재삽입·수량 증가·시각 변경이 없음을 확인한다. |
| 최종 완료 | 중복 검증 이후에만 `n+1` commit. 전달 2회, 완료 위치 `n → n → n+1`, 완료 1건·활동 2건 유지. 두 번째 consumer를 닫은 뒤에도 `n+1`이다. |
| position과 committed offset | 각 poll 이후 position은 `n+1`이어도 업무 완료 commit 전에는 committed offset이 `n`임을 함께 확인한다. |

DB commit 여부는 같은 영속성 컨텍스트의 엔티티나 flush 결과로 판단하지 않는다. `MySQLContainer.createConnection`으로 새 물리 연결을 열어 두 테이블 전체를 읽는다. snapshot에는 PK·식별자·상품·수량·초/나노초 시각이 포함되므로 행 개수만 같은 잘못된 재삽입도 탐지한다.

## 통제한 중단 방식

실험용 consumer는 운영 listener factory의 실제 설정을 복사해 생성한다. `enable.auto.commit=false`를 assertion하며 실제 parser·handler와 기존 MySQL 수동 DDL/JPA validate를 사용한다. Spring 업무 listener의 자동 시작은 이 실험에서만 끈다.

첫 consumer는 handler의 DB commit을 확인한 다음 **ack나 후속 offset commit을 요청하지 않고 유한 시간 내 close**한다. 자동/비동기 commit이나 종료 시 commit callback은 추가하지 않았다. 이 통제 방식은 과제에서 허용한 consumer 교체 실험이다. **OS 프로세스 강제 종료·네트워크 단절·실제 ack 패킷 유실을 재현한 실험은 아니다.** 실제 Spring error handler의 실패 정책은 선행 C07 테스트를 유지한다.

producer는 target을 한 번만 보낸다. 두 번째 전달을 만들려고 같은 JSON을 새 record로 재발행하지 않는다. 두 번째 consumer는 같은 group에 구독하여 broker에 남은 완료 위치에서 읽는다.

## 테스트 작성·실행 과정

| 순서 | 관찰 | 판단 |
| --- | --- | --- |
| 테스트 먼저 작성 | 저장 완료·offset 보류·consumer 교체·동일 record·DB 전체 snapshot·최종 offset assertion 작성 | 업무 코드 수정 전에 새 장애 경계를 실행 가능한 검증으로 정의 |
| 첫 컴파일 | `Consumer<? super String, ? super byte[]>` 반환값을 구체 타입에 대입해 Java 컴파일 오류 1개, 종료 1 | 테스트의 Spring factory 제네릭 사용 오류. 업무 중복 방지의 Red 증거로 세지 않음 |
| 테스트 구성 수정 | 운영 factory의 설정을 복사한 명시적 타입의 `DefaultKafkaConsumerFactory<String, byte[]>` 사용 | 유효 설정·기대값·운영 코드는 그대로 유지 |
| 첫 실제 실행 | 2026-10-29 10:38 KST, 신규 1개 통과·실패/오류/skip 0, BUILD SUCCESSFUL 16초·종료 0 | 기존 유일 제약·rollback 뒤 사실 비교가 이 재전달을 이미 보호함을 확인 |

```shell
./gradlew :apps:commerce-streamer:test \
  --tests 'com.loopers.interfaces.consumer.OrderActivityOffsetRedeliveryIntegrationTest' \
  --offline --console=plain
```

기존 구현으로 첫 실제 실행부터 통과했다. 인위적으로 운영 코드를 깨거나 기대값을 바꾸어 업무 Red를 만들지 않았다. 이번 결과는 **새 검증을 먼저 작성하여 기존 중복 처리 경계의 동작을 확인한 증분**이며, 새로운 중복 방지 알고리즘을 구현했다고 표현하지 않는다.

## 첫 실행의 실제 관찰

- topic: `order-events-offset-gap-e69484de-4800-4bfc-9be9-b2257783fdc5`
- group: `purchase-offset-gap-46ac775f-9a38-44e2-9d65-d4ae7d4f6408`
- producer가 반환한 위치: **partition 0·offset 0**
- eventId `order-confirmed:v1:42`, orderId 42, confirmedAt `2026-10-29T00:00:00.123456789Z`

| 관찰 시점 | handler 결과 | 완료 기록 | 상품 활동 | 실제 committed offset |
| --- | --- | ---: | ---: | ---: |
| 첫 consumer 준비 | 미처리 | 0 | 0 | 0 |
| DB commit 성공·첫 consumer 종료 | APPLIED | 1 | 2 | 0 |
| 새 consumer의 동일 record 처리·완료 commit | DUPLICATE | 1 | 2 | 1 |

전달 위치는 두 번 모두 동일한 topic·partition 0·offset 0이다. key와 payload bytes도 같다. 두 번째 처리 전후 두 테이블의 모든 저장 값과 PK가 같으며, 상품별 수량 2·1과 최초 확정 시각도 유지됐다. 테스트는 `offset_gap_verified` 한 줄로 실제 topic/group/위치·전달 수·offset 변화·DB 행 수·처리 결과를 기록한다.

## 관련 회귀와 자원 정리

2026-10-29 **10:39 KST**, 다음 명령은 **BUILD SUCCESSFUL / 42초 / 종료 코드 0**이었다.

```shell
./gradlew :apps:commerce-streamer:test \
  :apps:commerce-streamer:checkstyleMain \
  :apps:commerce-streamer:checkstyleTest \
  --offline --console=plain
```

| 범위 | 결과 |
| --- | --- |
| streamer 전체 테스트 | **8개 스위트·97개**, 실패·오류·skip **0**. 신규 C05 1개와 기존 96개를 포함 |
| ArchUnit | streamer 계층 검사 1개 통과, 위 97개에 포함 |
| Checkstyle test | 실제 실행, 위반 0 |
| Checkstyle main | 운영 소스 변경이 없어 UP-TO-DATE, 기존 위반 0 보고서 유지 |
| diff·문서 | `git diff --check` 통과. 실행 기록과 기존 설계·체크리스트를 연결 |

전체 회귀 중 C05는 다시 새 소유 자원으로 실행됐다. topic은 `order-events-offset-gap-247a33bb-3745-45c9-b18e-f25c7a5088d2`, group은 `purchase-offset-gap-3ddfd5f2-9240-4a5e-9fa3-6e3b4bbd58ae`였다. 관찰은 **partition 0·offset 0·전달 2회·committed [0, 0, 1]·완료 1건·활동 2건·APPLIED/DUPLICATE**로 첫 실행과 같았다. 두 실행의 별도 topic/group을 같은 실험 중 교체한 것으로 혼동하지 않는다.

poll·배정·send·commit·close에는 유한 deadline/timeout을 두었다. producer·두 consumer·AdminClient는 finally에서 닫고, Testcontainers가 자신의 Kafka/MySQL을 정리했다. 종료 후 실행 중 `org.testcontainers=true` 컨테이너는 0개였다. 사용자 DB·기존 topic/group·volume을 사용하거나 삭제하지 않았다.

JUnit XML과 [HTML 보고서](../../apps/commerce-streamer/build/reports/tests/test/index.html)는 후속 실행에서 바뀔 수 있으므로 명령·시점·범위·실제 관찰값을 위에 남긴다. 선행 API 862개·streamer 96개 기록은 당시 증거로 보존하며 이번 실행 건수에 중복 합산하지 않는다. API 코드를 이번에 변경하거나 API 전체 검사를 다시 실행하지 않았다.

## 남은 단계

1. **C04 배정 실험:** partition 2개에서 같은 group의 consumer 1→2→3 배정, 두 partition의 실제 읽기, 같은 key의 metadata를 확인한다.
2. **전체 경로와 W7 인계:** 실제 PG 확정→API 발행→streamer DB 전체 경로를 연결하고, 직접 발행 누락 1건·정상 소비 1건의 사건 사실·재현 코드·실행 revision을 남긴다(C01·C08·C09).
3. **최종 대조:** 변경 범위의 회귀와 11개 채점 증거·문서 정합성·자원 정리를 확인한다. 기술 글·커밋·PR·제출은 별도 작업이다.

이번 C05 통과는 DB와 Kafka가 하나의 트랜잭션이 되었다는 뜻이 아니다. 재전달은 실제로 두 번 발생했고, 기존 DB 제약과 사실 비교가 구매 효과를 한 번으로 유지한 결과다. 직접 after-commit 발행의 누락 가능성은 그대로이며 Outbox·랭킹을 추가하지 않았다.
