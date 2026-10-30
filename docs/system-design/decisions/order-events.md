# 주문 확정 이벤트 발행·소비 판단

기존 절 번호 참조: [2.11](../features/order-events/publication.md#211-주문-확정-이벤트-발행의-책임과-설정) · [3.7](../features/order-events/README.md#37-최초-pg-확정-시각과-사건-값) · [4.6](../features/order-events/publication.md#46-주문-확정-이벤트-발행).

기존 문서의 계약·절 번호·승인/검증 상태를 보존해 이동했다. 재편 자체는 제품 계약 변경이나 새 실행 검증을 뜻하지 않는다.

**현재 상태:** 2026-10-29 사용자 최종 승인 설계다. 아래 2026-10-28의 초안/보완 설명은 당시 판단 이력이며 현재 승인 상태를 대신하지 않는다. 승인된 구현 단위와 관련 실행 검증을 마쳤다. 결과·한계는 [검증 기록](../verification/order-events.md)을 따른다.

## A.34 주문 확정 사실의 직접 발행

**상태·근거 구분:** 2026-10-28 사용자와 사건 ID·확정 시각·AFTER_COMMIT·비동기 결과 관측·로그·producer 내부 재시도·유한 종료 대기 정책을 합의하고 발행 측 상세설계 작성을 승인받았다. 2.11·3.7·4.6·5.8은 그 정책의 상세 초안으로 작성했고 2026-10-29 최종 승인했다. 설계 검토는 구현·실험 결과가 아니다. GPT 검토는 제공한 코드 요약에 대한 제안으로 검토했고 사용자의 선택을 대신하지 않았다. 원문 요구는 최초 PG 확정 뒤 직접 발행·구매 활동 저장·두 공백 검증이며 Outbox·랭킹 API는 이번 주 제외다.

### 문제·제약·선택과 비용

|판단|선택·이유|대안이 유리한 조건·포기한 것|
|---|---|---|
|주문과 구매 활동의 책임|주문은 확정 사실만 전달. 후속 저장을 동기 업무 호출하지 않음|직접 호출은 완료 흐름이 명확하지만 주문이 후속 장애·변경을 함께 알아야 함. 이벤트는 전달·지연·관측 책임을 새로 만듦|
|사건 식별|`order-confirmed:{orderId}` 유도. 주문당 최초 확정 하나라 별도 저장 이익이 작음|UUID 저장은 사건 식별 규칙을 주문 ID에서 분리할 수 있지만 열·생성·정합성 비용 추가. 재확정 요구가 생기면 유도 전제를 재검토|
|ID와 스키마|schemaVersion은 wire에만. 같은 사실의 표현 변경으로 ID를 바꾸지 않음|버전 포함 ID도 가능하나 schemaVersion 변경과 사건 신원 변경을 혼동할 비용. 현재 v1 밖의 호환 정책은 미구현|
|시각|최초 CONFIRM에서 UTC·마이크로초 시각을 한 번 생성해 저장·snapshot 공유. 기존 ZonedDateTime 유지|Instant로 바꿀 필수 이유가 없고 불필요한 타입 전환을 피함. DATETIME(6) 목표와 실제 ORM/JDBC 왕복을 검증해야 함|
|발행 연결|Spring AFTER_COMMIT listener. snapshot은 TX 안에서 생성|기존 AfterCommitRunner 재사용은 작은 변경, 반환 뒤 orchestration은 순서가 명시적. listener는 사건과 전송 책임을 분리하지만 암묵적 호출 추적·TX 밖 등록 무시를 알아야 함|
|내부/외부 모델|domain 불변 사건·domain 포트, infrastructure wire DTO·Kafka adapter|동일 DTO 공유는 타입 수가 줄지만 내부 사건이 외부 JSON 버전과 결합. 분리안은 얇은 변환을 유지하는 비용|
|비동기 결과 관측|send 뒤 Future만 관측, 별도 @Async 없음|동기 완료 대기는 응답 전 broker 결과를 확인할 기회가 있지만 요청 지연을 늘리며 소비 완료·DB/broker 원자성은 못 줌. @Async는 호출 자체를 옮길 수 있으나 executor 거부·큐·종료 공백 추가|
|재시도·관측|producer 내부 재시도와 로그만. 자체 재발행 없음|영속 재발행·Outbox는 commit→send 공백 복구에 유리하지만 새로운 상태·수명·중복 처리가 필요해 범위 제외. 재발행 일반이 모두 Outbox라는 뜻은 아님|
|설정 격리|이름 붙인 전용 factory/template, 공통 props 복사 뒤 retries 상속 제거|공통 producer 설정 일괄 변경은 간단하지만 consumer·다른 발행 경로까지 영향. 전용 bean·namespace·qualifier를 검증하는 비용 수용|
|정상 종료|HTTP graceful와 producer 15초 close, phase 대기 30초|더 긴 close는 정상 전달 기회를 더 주지만 종료가 길어짐. 30초 전달 예산을 종료 때 모두 주지 않는 비용을 수용|

**중간 검토 후 추가 합의:** 공통 설정 복사·전용 override 이후 `transactional.id`가 있거나 전용 factory가 transaction-capable이면 기동 오류로 거절한다. 비트랜잭션 발행이라는 기존 선택을 조용히 바꾸지 않기 위해서다. 충돌 설정을 제거하고 실행하는 대안은 기동을 이어갈 수 있지만 설정 오류를 숨길 비용이 있어 채택하지 않았다. 현재 공통 설정에 실제로 충돌이 발견됐다는 뜻은 아니며 2.11·P5에서 확인할 조건이다. 품목의 합산 전 검증·독립 DB 연결의 commit 관측·모든 partition의 누락 검사도 기존 계약을 명확히 하는 보완이며 새 전달 보장을 추가한 것은 아니다.

초기 제안의 max.block=1초·request=3초·delivery=10초·retries=3은 빠른 실패 관측을 우선한 안이었다. 그러나 응답시간 요구·실측이 없는 상태에서 정상 최초 metadata 확보와 일시 지연까지 조기에 포기할 근거가 부족했다. 사용자는 max.block=5초·기존 request=20초·delivery=30초·retries 기본값으로 시간 예산을 우선하는 수정안에 동의했다. 2.11이 현재 초기값의 단일 기준이며 이전 수치는 구현값이 아니다. 수정안도 실측된 최적값은 아니고 실제 cold start·오류·종료 관찰로 재검토한다.

### 공식·코드 근거와 입증하지 않은 것

- **공식 소스 확인:** 로컬 Kafka clients 3.8.1의 ProducerConfig는 idempotence의 acks=all·retries>0·in-flight<=5 조건, delivery/request/linger 관계, metadata·buffer만 다루는 max.block 설명, 횟수보다 delivery timeout 중심 제어 권고를 포함한다. CommonClientConfigs는 backoff·jitter를 설명한다. KafkaProducer.close(Duration)는 종료 대기 후 미완료 전송 강제 종료 경로를 가진다. 설정 의미·구현 읽기이며 실제 runtime의 동작 확인은 아니다.
- **공식 소스 확인:** 로컬 Spring Kafka 3.3.4 DefaultKafkaProducerFactory의 setPhysicalCloseTimeout·getPhase·stop·destroy 경로를 읽었다. 기본 구조를 근거로 HTTP 정리 뒤 producer 종료를 설계하지만 실제 bean 순서·스레드 정리는 4.6에서 확인한다. Spring Framework 6.2의 TransactionalEventListener·TransactionPhase 계약에 따라 commit 성공 후 처리·fallbackExecution=false를 명시한다. 구체 버전의 afterCompletion 예외 처리에만 기대지 않고 서비스 로그·명시적 동기 오류 처리로 정책을 표현한다.
- **코드 확인:** 현재 PG는 공통 applier·TX B·Order 잠금·CONFIRM/FINAL_UNCHANGED를 구분한다. 기존 OrderModel에 confirmedAt은 없고 품목은 LAZY·생성 뒤 고정이다. 공통 YAML은 retries=3·request timeout=20초·backoff=500ms·type headers=false다. 공통 factory는 KafkaProperties에서 map을 만든다. YAML 선언은 실제 적용 증거가 아니다.
- **코드/공식 매핑 기준:** 기존 JPA의 NORMALIZE_UTC·jdbc.time_zone=UTC와 ZonedDateTime을 유지한다. MySQL 8.0 Reference Manual의 Fractional Seconds in Time Values가 설명하는 마이크로초 저장 정밀도를 목표로 삼지만 실제 DATETIME(6) 생성·왕복·JSON은 미검증이다. 로컬 캐시 jar 버전·Docker image 선언은 실행 중인 의존성·서버 버전 확인을 대신하지 않는다.
- **사용자 제공 사실:** 보존할 실데이터가 없어 이번 신규 컬럼의 이관·backfill은 제외한다. DB 조회 결과가 아니며 유효한 POINT·PG 보존 테스트의 제거 근거도 아니다.

### 남은 한계와 재검토 조건

DB commit→send 사이에는 여전히 미발행 공백이 있다. idempotent producer·안정 ID·정상 종료 대기·동기 완료 대기 어느 것도 이를 제거하지 않는다. broker에 같은 사건의 별도 record가 생길 수 있고 구매 효과의 유일성은 소비 DB 계약이 맡아야 한다. timeout이나 실패 Future는 broker 미기록의 일반 증거가 아니다. 로그만으로 자동 복구나 완전한 관측도 보장하지 않는다.

재확정·멀티테넌트 ID 공간·다중 broker·더 강한 HTTP 응답시간/복구 보장·민감 payload·consumer 스키마 진화 요구가 생기면 관련 판단을 다시 검토한다. 실제 cold start 실패·요청 지연·종료 중 미완료·DB/payload 시각 차이가 관측되면 먼저 유효 설정·실행 경계를 확인하고, 계약 변경이 필요하면 사용자에게 판단받는다. 모든 검토를 추가 실험으로 확장하지 않으며 선택적 글감 실험은 주제 선정 후 별도 승인한다.

## A.35 구매 활동 소비의 정합성과 전달 경계

**상태:** 2026-10-28 사용자가 소비 측 상세설계 문서 작성을 승인했다. 유효 committed offset이 없는 새 group의 `earliest` 시작은 사용자가 선택했다. 상세 검토·보완 후 2026-10-29 전체 설계를 최종 승인했으며 구현·실행 검증은 하지 않았다.

기준은 `docs/system-design/features/order-events/consumption.md`다. 3~5절에 완료/활동 단일 TX·정확한 사실 비교·완료 unique 경쟁과 rollback 후 새 TX 재확인, 6절에 전용 record listener·DB 종료 후 ack·중단/재시작, 7절에 실제 전달·두 공백·DB 효과 검증, 8절에 문제·대안·비용·재검토 조건을 둔다. 근거를 중복 복사하지 않는다. 기존 주문·PG·상품 업무를 소비자에서 다시 실행하거나 DLQ·Outbox·자동 재발행을 추가하지 않는다.

### 전체 Claude 검토 후 사용자 합의 — A1~A4·B·C

- **회귀와 실제 발행 분리:** 기존 API 회귀는 테스트 전용 기록용 포트로 대체하고 실제 adapter 검증은 별도 컨텍스트에서 명시한다. broker 부재의 metadata 대기와 공유 topic 오염을 막으면서 기존 회귀 의미를 유지한다. 모든 테스트에 실제 broker를 붙이는 대안은 실행/기동 비용과 callback 시간 영향을 늘리므로 채택하지 않았다. fake를 broker 근거로 쓰지 않는 비용·실제 adapter 선택 검증은 publication 2.11이 기준이다.
- **손상 우선 분류:** 저장 eventId 유도·고정 타입/버전과 품목 정합성부터 검사하고, OR 두 완료 행도 STORAGE_INTEGRITY_ERROR로 정한다. 정상 저장 상태와 다른 incoming만 충돌이다. 추가 구매 차단 동작은 유지하되 오류 원인을 구현자가 추측하지 않도록 분류를 명확히 했다. 소비 3·5절·C3이 기준이다.
- **공유 테스트 DB:** 기존 MySQL 테스트 컨테이너의 같은 DB를 사용하고 독립 시나리오 전 두 구매 테이블을 명시 정리·0행 확인한다. 별도 MySQL/스키마는 격리에 유리하지만 자원·권한·초기화 구성을 늘리므로 사용자는 공유안을 선택했다. 같은 시나리오의 listener/consumer 재시작 동안 데이터 보존·직렬 실행이 비용이며 소비 6절이 기준이다.
- **공유 개발 broker:** 사용자가 뜻한 운영 Kafka는 실서비스가 아닌 로컬 Docker Kafka임을 확인했다. 이를 공유하고 실행별 topic/group·bootstrap/admin 주소·소유 자원 cleanup을 통제한다. 새 Kafka Testcontainers/이미지 변경은 하지 않는다. 자동 준비 대신 Docker 사전 기동·환경 의존과 접속 실패를 명시적으로 보고하는 비용을 수용한다. publication 2.11이 기준이다.
- **종단만 별도 JVM:** 다른 검증은 각 앱의 일반 테스트로 두고 C10에서만 streamer 별도 JVM을 사용한다. 앱 classpath/scan을 섞지 않는 대신 bootJar 준비·자식 설정·배정 확인·종료 관리 비용을 수용한다. callback 1초 제한은 유지하고 준비된 종단 결과와 cold start 한계를 구분한다. 소비 7절이 기준이다.

사용자는 위 방향을 합의한 뒤 문서 보완 착수를 별도로 승인했다. 이 문단은 보완 당시의 기록이며 실제 MySQL/Kafka/JVM 검증 완료를 뜻하지 않는다. 현재 설계 승인 상태는 문서 상단을 따른다. 기존 Kafka 이미지·테스트 컨테이너 설정은 코드/선언 확인에 한하며 실행 환경 버전은 구현 검증에서 기록한다.

### 재검토 잔여 보완과 시작 위치 재확인

사용자는 재검토 잔여 A1·A3·B·C 보완과 전용/테스트 consumer의 `allow.auto.create.topics=false` 적용을 승인했다. fake의 공통 상위 클래스·Spring TestBean override와 테스트 전역 guard/실제 marker를 정해 적용 누락을 기동 실패로 드러낸다. 독립 streamer 컨텍스트는 각자 자기 테이블 create로 준비하고, 보존 구간은 같은 컨텍스트/JVM 안에서 끝내 테스트 클래스 순서 의존을 없앤다. validate는 보존한 DB로 새 프로세스를 띄우는 향후 별도 검증에 적용하며 현재 C1~C10의 선행 조건으로 요구하지 않는다.

새 group의 earliest 선택도 유지한다. latest는 client 기본값이지만 새 group 시작 전에 보존된 주문 확정 사실을 건너뛰는 비용이 있다. 이 기능은 구매 사실 누락을 피하는 쪽을 선택하고 재전달 비용은 DB 동일 사실 비교로 감당한다. 유효 offset이 있는 기존 group의 재개 위치·retention 밖 복구 보장은 바뀌지 않는다. 자동 topic 생성 금지는 offset reset과 별개로 명시적 topic 준비와 정리 책임을 지키기 위한 선택이다. 기본 true는 준비가 간단하지만 잘못된 이름/순서에 따른 생성과 cleanup 뒤 재생성 위험이 있어 채택하지 않았다. 이 client 설정은 producer·다른 client나 broker 전역의 자동 생성까지 막지 않는다.

구체 계약은 publication 2.11·소비 6~7절이 원본이다. P2 hook/연결 여유·P6/P8 준비 연결·UTF-16/32 거절 단계·식별 충돌 방어 분기도 명확히 했으며, 새 기능·선택적 실험을 추가하지 않았다. 근거는 재검토 제안과 로컬 Spring Test 6.2.5의 TestBean/ContextCustomizerFactory 소스 확인이다. 구현·실행 검증은 아직 하지 않았다.

### 최종 승인과 DLQ 범위 — 2026-10-29

사용자는 설명용 요약과 추가 질의로 확정 사건·시각·JSON·전송 관측·전용 producer·동시 오류·streamer/JVM·DLQ 범위를 확인한 뒤 현재 설계 확정 및 관련 파일 스테이징을 요청했다. 이를 현재 기준 설계의 최종 승인으로 기록한다. 현재 중단형 오류 처리 구현/검증이 우선이며 DLQ는 후속 확장 후보다. 실습의 명시적 제외와 우리가 선택한 미구현 범위를 구분하고, 근거·한계·후속 판단 항목은 소비 상세설계의 DLQ 절을 따른다. 실제 코드 구현·테스트 통과나 다음 구현 단위의 착수 허락으로 해석하지 않는다.

### 회귀 격리 범위 축소 — 2026-10-29

- **문제:** 구현 검토에서 전체 Spring 컨텍스트에 적용한 guard가 브랜드·포인트 등 PG 확정을 실행하지 않는 회귀에도 주문 이벤트 부모 상속을 강제했다. 상속을 공통 annotation으로 치환해도 무관한 테스트에 설정을 요구하는 원인은 남는다.
- **사용자 승인 선택:** 전역 guard·mode marker·전체 회귀용 공통 부모를 제거하고, 실제 최초 PG 확정을 실행하는 회귀에만 명시적인 test bean override를 둔다. 기존 PG 전용 fixture는 활용하되 새 전체 앱 annotation·범용 bean 교체 프레임워크는 만들지 않는다. 정확한 적용 계약과 P9는 publication 2.11·4.6을 따른다. 이 선택은 앞선 상속/guard 합의를 대체하며 PG·이벤트 제품 계약은 바꾸지 않는다.
- **근거·예외:** 현재 브랜드 검증의 과거 주문은 POINT fixture이고 삭제 상품 확정은 최초 PG 확정 전 거절이다. 반면 상품 캐시 무효화 검증은 실제 PG SUCCESS를 만들어 격리가 필요하다. 스키마 전환 검증은 PG 결제 시작까지만 수행하므로 별도 발행 교체가 필요하지 않다. 이 판단 당시에는 코드 정적 확인이었다. 이후 코드 축소와 관련 회귀를 실행했으며 결과는 별도 검증 기록에 둔다.
- **대안·비용:** 전역 guard는 적용 누락을 기동 실패로 탐지하지만 모든 컨텍스트를 주문 이벤트 설정과 결합한다. 범위를 좁히면 이 결합과 조립 코드를 줄이는 대신 새 PG 확정 테스트의 fake 누락을 전역 자동 차단하지 못한다. 관련 테스트의 실제 호출 경로와 포트 선택을 명시적으로 검토·검증한다. mock/fake 기록을 broker 저장 근거로 사용하지 않는 원칙은 유지한다.
- **상태:** 기준 설계의 범위 변경을 반영했다. 이후 코드 축소·삭제와 회귀 재실행을 마쳤고 `779512f`에 반영했다. 종전 849건 결과는 축소 후 통과 근거가 아니다.

### 발행 검증 전용 임시 스키마 — 2026-10-29

- **문제:** 실제 broker 발행 검증(P4·P6~P8)은 SpringApplicationBuilder로 앱을 create/validate 기동·재기동한다. 기본 테스트 스키마를 쓰면 캐시된 다른 테스트 컨텍스트의 데이터·DDL과 섞인다.
- **사용자 승인 선택:** 기존 MySQL 테스트 컨테이너 안에 임시 스키마 `order_event_kafka`를 만들어 쓰고 종료 시 삭제한다. 새 DB 컨테이너는 만들지 않는다. streamer·C10의 별도 스키마 허용으로 확대하지 않는다.
- **비용·한계:** 테스트가 root 접속으로 스키마 생성·권한 부여·삭제를 수행한다. 정리 일부가 실패해도 나머지를 시도하고 오류를 보존하지만, JVM 강제 종료 시 스키마가 컨테이너 수명 동안 남을 수 있다(컨테이너는 테스트 종료와 함께 사라짐).
