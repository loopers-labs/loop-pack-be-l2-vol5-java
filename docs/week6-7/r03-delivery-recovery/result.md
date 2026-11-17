# R03 전달 복구 검증 결과

2026-11-04 서울 시간에 실행한 격리 실험 `6724aec69c`의 합성 주문이다. W6 실행물은 `42d8ed8`에서 빌드했다.
세 주문은 기존 주문 HTTP API, 실제 테스트 PG, MySQL, Kafka, Streamer로 처리했다. 품목은 주문마다 상품 7 두 개와 상품 8 한 개다.
Kafka 토픽은 `order-events-6724aec69c`, consumer group은 `purchase-6724aec69c`였다.

| 주문 | 저장된 이벤트 UUID | 확정 시각 (KST) | Kafka 파티션과 offset |
|---|---|---|---|
| A · 1 | `69c3e428-a28f-411e-b778-efe11b2894f5` | 21:18:05.991277 | 복구 후 1/0 |
| B · 2 | `2d37a514-bfcd-41e1-af80-ea571c7c4284` | 21:18:48.393018 | W6 0/0, 검증용 재발행 0/1 |
| C · 3 | `88826421-b87d-4b06-8ed5-cd1c0b25b019` | 21:22:11.544118 | 최초 1/1, Relay 재시작 뒤 1/2 |

## W6 누락에서 복구

- A의 주문 커밋 뒤 직접 발행 메서드 첫 명령에서 디버거로 스레드를 멈추고 실험 API JVM을 종료했다. 별도 DB 연결에서 확정 주문·PG SUCCESS·소비된 재고 예약을 읽었다. B까지 준비한 시점에 Kafka에는 B의 record 한 건이 있었고 A의 Inbox와 구매 활동은 0행이었다.
- 같은 DB에 `011`을 적용하고 A의 ID와 저장된 UUID로 복구했다. 첫 실행은 `CREATED`, 두 번째는 `ALREADY_PRESENT`였다. A의 Outbox는 `PUBLISHED` 한 행이고 Inbox 한 행과 구매 활동 두 행을 얻었다. 이 단계에서 B의 Outbox는 없었고 B의 기존 Inbox·활동 ID와 수량이 그대로였다.
- 검증을 위해 B를 명시적으로 한 번 재발행했다. Streamer는 같은 UUID를 `ALREADY_PROCESSED`로 처리했다. B의 기존 활동은 그대로였고 같은 명령을 반복하면 `ALREADY_PRESENT`를 반환했다.
- A 복구와 B 재발행 전후의 PG create와 lookup은 각각 2회로 같았고 callback은 0회였다. PG 외부 거래 두 건의 식별자, 확정 시각과 주문 품목, 재고 예약 상태·버전, 상품 재고는 일치했다.

## Kafka 수신 확인 뒤 Relay 중단

- C는 Kafka 수신 확인 뒤 Outbox 상태 저장 메서드 첫 명령에서 스레드를 멈췄다. 이때 별도 DB 연결에서 `PENDING:1`과 커밋된 Inbox·활동을 확인하고 API JVM을 종료했다.
- 같은 DB와 Kafka로 API를 재시작했다. C의 Outbox는 `PUBLISHED:2`가 됐고, 브로커에서 서로 다른 offset의 동일 키·payload record 두 건을 읽었다. Streamer는 두 번째 record를 `ALREADY_PROCESSED`로 판정했다.
- C의 Inbox 한 행과 활동 두 행의 ID·수량은 그대로였다. 상품 7의 최종 재고는 14, 상품 8은 17이었다. PG 거래는 합계 세 건이고 PG 포인트 차감은 0건이었다. 최종 그룹 offset은 파티션 0에서 2, 파티션 1에서 3이며 두 파티션의 lag는 0이었다.

## 검사와 재현

- Commerce API 494건, Streamer 22건, 테스트 PG 25건, Kafka 1건으로 전체 `check` 542건이 통과했다. 실패·오류·건너뛰기는 0건이었다. Checkstyle과 ArchUnit을 포함한다.
- 잘못된 복구 프로필 조합은 실행 파일에서 DataSource 시작 전에 거절되는 것을 확인했다. MySQL 검사에서는 UUID 불일치, 기존 수량·UUID 충돌 거절, JSON 필드 순서와 공백 차이를 허용하는 재실행을 확인했다.
- 이 실험은 중단 위치를 디버거로 제어한 뒤 해당 프로세스를 종료했다. PG callback은 실험 옵션으로 생략하고 주문 대사 API를 사용했다. 실험 스크립트를 실행하면 PG 거래 ID, Kafka 원문과 위치, DB 행 식별자가 로컬 `build/r03-lab/<실행ID>/evidence.json`에 기록된다.
- [재현·운영 절차](operations.md)와 [전달 경계 기술 정리](technical-note.md)를 참고한다.

## 통합한 초기 준비·R01·R02 검증 근거

과거 인계·준비·개발 계획의 고유 근거를 통합했다. 최종 R03 결과와 서로 중복되므로 합산하지 않는다.

| 단계 | 실제 기록과 한계 |
|---|---|
| 초기 준비 ·2026-11-04 | Java21.0.10·Docker Linux29.8.0. KafkaTestFixtureTest 실제1건 성공. Admin API timeout10초가 request30초보다 짧아 최초 실패했고 테스트 request5초로 보완 후 성공. Streamer compileTestJava는 당시NO-SOURCE여서 업무 검사를 뜻하지 않음 |
| 원문·사용자 변경 | 준비 시 원문4개 복사 전후 SHA-256 일치. 원문 후행 공백 보존. 당시 사용자 JMeter SHA-256 F51DC3363E79FB6EEB15846ACA55C9028CB58F207C8F89CC973E2CE3B3FC89C0 보존 |
| R01 계약/저장 | 앱별 DTO·최초 UUID·createdAt 보존 관련43건. 별도 MySQL의008 SQL에서 기존 POINT/PG 시각·null UUID 보존,009에서 동시 중복·충돌·중간 품목 실패 전체 rollback |
| R01 발행·소비 | 관련42건. 직접 발행/전송 실패 뒤 주문 확정 보존·중복·충돌·계약 오류·실패 보관 commit 뒤 offset 진행 |
| R02 집중 | 관련45건. 실제2-partition의 consumer1→2→3 배정, 품목 rollback·offset 유지, 업무 commit 후 ack 전 중단의 같은 offset 재전달/no-op |
| R02 Outbox | 직렬화/insert 직후 실패의 원자적 rollback, 재시도/BLOCKED/재개. broker 확인 뒤 상태 저장 실패 주입 후 새 relay가 같은 key/payload를 별도 Kafka offset 두 개로 발행 |
| R02 전체 | commerce-api493·streamer22·kafka1=516건 성공. 독립 DB에000~011 적용 후 재개 프로필 시작·validate·대상 한 행 갱신·종료 확인 |

초기 준비 검사는 주문 실험/전체 회귀를 대신하지 않았다. 직접 발행 revision42d8ed8과 최종 Outbox를 구분한다.
R03의 전체542건과 A/B/C 실험은 위의 최종 결과 절을 따른다. 환경의 현재 실행법은 [환경 안내](../environment.md)로 통합했다.
