# 주문 확정 이벤트의 로컬 검증 절차

기존 [설계](../../system-design/use-cases/order-confirmed-event.md)의 발행 실패·정상 소비를 같은 로컬 환경에서 관찰하고, streamer 재시작 후 DB 사실과 offset이 유지되는지 확인한다. 자동화 회귀 테스트는 모듈별로 유지한다. 이 실행 도구는 두 애플리케이션을 별도 프로세스로 실행하며 Gradle의 `test`나 `check`에는 연결하지 않는다.

## 재현

Java 21, Python 3, Docker가 필요하다. 저장소 루트에서 실행한다.

```bash
python3 docs/verification/order-confirmed-event/run.py
```

실행 도구는 현재 소스의 API·streamer·테스트 PG JAR를 빌드한다. 고유 이름과 사용 가능한 loopback 포트로 MySQL 8.0·Redis 7.0·Kafka 3.8.1을 새로 띄우고, `init.sql`과 구매 활동 이관 SQL을 적용한다. 기존 로컬 컨테이너·DB·topic·group은 사용하지 않는다. 실행 로그와 `observations.json`은 출력된 임시 디렉터리에 남기며, 성공·실패 모두 시작한 앱과 컨테이너를 종료한다.

품목은 상품 7×2·8×1로 고정하고 두 상품의 초기 재고는 각각 10이다. 테스트 PG는 AUTO 모드로 1초 뒤 성공 callback을 보내며, 확정 시각은 실제 실행 Clock의 값이다. 주문·거래·이벤트 ID는 생성 결과를 사용한다.

## 전송 실패와 정상 소비

1. 전용 topic의 `max.message.bytes`를 1로 낮춘다. 기존 주문·결제 API와 테스트 PG를 통해 주문 A를 확정한다.
2. API가 비동기 전송 오류를 관찰했는지, 주문·재고는 반영됐는지 확인한다. 해당 topic의 세 partition end offset이 모두 0이고 A의 완료 기록·구매 활동이 없는지 조회한다.
3. 크기 제한을 1MiB로 복구하고 같은 API·PG 경로로 주문 B를 확정한다.
4. 실제 broker record와 B의 저장 품목·수량·확정 시각을 대조한다. streamer의 완료 기록 1행·활동 2행과 group committed offset을 확인한다.

`max.message.bytes`는 broker가 받아들이는 record batch 크기의 제한이다. 이 실행은 broker가 batch를 거절한 전송 실패이며, `RecordTooLargeException`을 관찰한다. [Kafka 3.8 topic 설정](https://kafka.apache.org/38/generated/topic_config.html#max.message.bytes)

기존 `OrderConfirmedKafkaDeliveryIntegrationTest`의 send 직전 spy 차단도 유지한다. 그 테스트는 호출 이전의 누락 경계를, 이 로컬 실행은 commit 이후 broker 거절과 실제 소비 DB 결과를 확인한다. 둘 다 프로세스 강제 종료나 응답을 잃은 timeout 실험으로 표현하지 않는다.

## 재시작과 중복 reconcile

streamer의 local 설정은 `ddl-auto=validate`다. 실행 도구는 이 속성을 덮어쓰지 않고 streamer 프로세스를 종료한 뒤 같은 DB·topic·group으로 다시 실행한다.

- A는 완료 기록·활동 0행을 유지해야 한다.
- B는 완료 기록 1행·활동 2행을 유지해야 한다. 활동의 행 ID·내용·생성/수정 시각을 재시작 전후로 비교한다.
- group committed offset이 재시작 전과 같아야 한다.
- A·B에 중복 reconcile을 호출해도 broker end offset·완료 기록·활동·재고가 변하지 않아야 한다. 누락 A의 자동 재발행도 없어야 한다.

이 검증은 단일 broker·복제 1과 정상 프로세스 종료·재시작을 사용한다. 복제 장애·네트워크 분할·OS 강제 종료에 대한 보장은 확인하지 않는다.

## 실행 결과 보관

실제 생성된 주문·거래·이벤트 ID, topic·group·접속 주소, DB 결과와 offset은 실행 도구가 출력하는 임시 디렉터리의 `observations.json`과 앱 로그에서 확인한다. JSON·로그와 일회성 관찰 보고서는 로컬에 보관하며 Git 제출 대상에서 제외한다. 저장소에는 실행 도구와 이 절차를 남기고, 제출할 핵심 결과는 PR 또는 기술 글에 요약한다.

## 관련 회귀 검증

```bash
./gradlew :apps:commerce-api:check :apps:commerce-streamer:check
```
