# 프로젝트 작업 지침

이 파일은 AI의 작업·설계 문서 작성 지침이다. 실제 설계 내용은 별도의 설계 문서에 작성한다.

## 기본 작업 원칙

- 합의한 계약·기대값·패키지 의존을 따른다. 미정 정책은 먼저 질문한다.
- 이번 기능에서 변경할 책임·파일·관련 테스트를 먼저 제안한다.
- 작은 기능을 구현하고 diff와 관련 테스트·lint·ArchUnit 결과를 확인한다.
- 검사를 통과시키기 위한 테스트·기대값·규칙 삭제나 완화는 하지 않는다.
- 정책·검사 기준 변경이나 범위 밖 개편은 이유와 영향을 설명하고 확인을 받는다.

## 참고 문서

| 문서 | 역할 |
| --- | --- |
| [W8 채점 기준](docs/week8/grading-criteria.md) | 11월 8일 BASIC 원문 9항목과 멘토 추가 공지의 적용 범위 |
| [W8 과제 원문](docs/week8/assignment-original.md) | 2026-11-08 제공된 구현·검증·제출 요구 원문 보존. 동기 발급·초과 거절, Set/Lua·master 수용, 기본 닫힘·명시적 재개 |
| [W8 학습 자료 원문](docs/week8/learning-original.md) | 요청률·처리량·동시성, admission/backpressure, 실행 token·총량, fail-closed·TTL 회수 한계의 개념 근거 |
| [W8 구현 체크리스트](docs/week8/implementation-checklist.md) | Redis 선별·MySQL 예약·서버 발급의 5단계와 실제 구현 상태, BASIC 실험의 별도 근거 |
| [W7 채점 기준](docs/week7/grading-criteria.md) | 이전 주 Transactional Outbox·단일 relay·W6 누락 복구 과제의 11항목과 유지할 회귀 기준 |
| [W7 과제 원문](docs/week7/assignment-original.md) | 2026-11-03 제공된 구현·검증·제출 요구 원문 보존. 확정 TX의 Outbox 공동 저장, 실제 broker 중복 발행과 같은 실행의 두 사례 복구 |
| [W7 학습 자료 원문](docs/week7/learning-original.md) | 로컬 원자성·불변 payload·relay·at-least-once·재개·관찰·보존의 개념 근거 |
| [W7 구현 체크리스트](docs/week7/implementation-checklist.md) | W7 11항목의 새 상태, 현재 W6 코드 출발점·영향 책임·미정 선택·진행 순서. 문서 저장과 실제 구현/검사를 구분 |
| [W7 전체 검사·증거 보완](docs/week7/final-verification.md) | 이전 전체 검사의 연결 한도 실패와 API 테스트 context cache 조정, 양 앱 전체 회귀·정적 검사와 W6 실제 사건 식별자 보완의 최신 실행 범위·결과 |
| [W7 단일 relay TDD 기록](docs/week7/outbox-relay-tdd-log.md) | API 내부 기본 OFF·순차 10건·12초 예산·5→60초 재시도, 짧은 Outbox 상태 TX와 실제 DB/Kafka 전달 검증. 두 중단/consumer 재발행 전체 E2E와 W6 보충은 후속으로 구분 |
| [W7 Kafka 통신 장애·재개 TDD 기록](docs/week7/outbox-kafka-outage-tdd-log.md) | 소유한 broker pause/unpause의 실제 전송 실패·SQL backlog 관찰·동일 PENDING 재개를 검증한다. 준비용 probe와 실제 사건, 시도 횟수와 broker record 수, 생산 코드와 테스트 전용 설정을 구분하며 최종 전체 검사는 별도 |
| [W7 relay 운영·보존 안내](docs/week7/outbox-operations-retention.md) | 현재 설정의 단일 relay 실행·장애 후 같은 행 재개, 소비 실패 재시작·관찰, Outbox/Kafka/소비 기록의 보존 관계와 자동 복구 한계를 정리한다. 운영 명령은 실행 증거가 아니며 보존 일수·삭제 정책을 새로 확정하지 않음 |
| [W7 중단·재발행 E2E 기록](docs/week7/outbox-republication-e2e-tdd-log.md) | 실제 주문 HTTP·PG·Outbox·Kafka·별도 streamer JVM/DB의 두 중단 후 새 relay 재개, 실제 별도 offset 재발행과 APPLIED/DUPLICATE·PK/수량 불변. 생산 코드 미변경이며 W6 과거 누락 보충은 후속 |
| [W7 W6 누락 Outbox 보충·복구 기록](docs/week7/w6-outbox-backfill-tdd-log.md) | 사용자 Q06 선택의 실제 W6 직접 발행 정상/누락 준비를 같은 DB·broker·PG로 이어 복구. 내부 명시 주문 보충·기존 행 비교/보존·실제 rollback/경합·누락 생성·기소비 PK 보존·PG/업무 추가 효과 0·두 번째 복구 불변을 검증. 최종 W7 전체 대조는 별도 |
| [W7 Outbox 공동 저장 TDD 기록](docs/week7/outbox-atomicity-tdd-log.md) | 공통 BEFORE_COMMIT·MANDATORY 기록, 불변 topic/JSON·추가 DDL·실제 SQL 후 전체 rollback·기존 PG GET 복구의 첫 저장 증분. relay·중단 재발행·W6 backfill은 후속이며 사용자 DB 미변경 |
| [W6 채점 기준](docs/week6/grading-criteria.md) | 이전 주 주문 확정 이벤트·Kafka·상품 구매 활동 과제의 11개 평가 항목, 회귀와 W7 재현 준비의 근거 |
| [W6 과제 원문](docs/week6/assignment-original.md) | 2026-10-27 제공된 구현·검증·제출 요구 원문 보존. 직접 after-commit 발행과 구매 활동 소비, Outbox·랭킹 구현 제외 |
| [W6 학습 자료 원문](docs/week6/learning-original.md) | 이벤트·Kafka 배정/offset·전달 보장·DB 중복 방지·발행 누락의 개념 근거 |
| [W6 구현·검증 체크리스트](docs/week6/implementation-checklist.md) | W6 공식 11항목의 새 상태, 기존 코드 출발점·영향 파일·미정 선택·진행 순서. 문서 작성과 구현/실행 완료를 구분 |
| [W6 주문 확정 이벤트 모델 TDD 기록](docs/week6/order-confirmed-event-tdd-log.md) | 사용자 선택 Q01/Q02, 생산자 불변 이벤트 모델의 신규 40개·관련 183개와 API Checkstyle 검증. 당시 after-commit·Kafka·소비 DB는 미연결이며 후속 발행 증거는 다음 기록 참조. 사용자 DB 미접근 |
| [W6 구매 활동 소비 TDD 기록](docs/week6/purchase-activity-consumer-tdd-log.md) | Q03의 두 테이블·필드 비교와 Q04의 실패 중단·같은 group 재시작 선택. streamer parser·공동 DB TX·유일 제약/충돌·실제 listener/ack와 전용 설정의 증분. 당시 후속이었던 DB commit 뒤 offset 공백은 다음 기록 참조. 사용자 DB 미접근 |
| [W6 offset 공백 재전달 TDD 기록](docs/week6/purchase-activity-offset-redelivery-tdd-log.md) | 실제 DB commit 뒤 offset 미커밋 종료·같은 group 새 consumer의 동일 record 재전달. 실제 committed offset n→n→n+1, 두 테이블의 PK·필드 불변을 검증. C07 listener DB 실패와 구분하며 생산 코드·사용자 DB 미변경 |
| [W6 Kafka 배정·key 매핑 TDD 기록](docs/week6/kafka-group-assignment-tdd-log.md) | DB 없는 전용 Kafka probe에서 partition 2개·consumer 1→2→3의 실제 배정/수신과 동일 key metadata를 검증. 운영 설정·사용자 DB·기존 topic/group 미변경, 단일 broker 보장 한계 구분 |
| [W6 정상 PG→구매 활동 전체 흐름 TDD 기록](docs/week6/normal-pg-purchase-activity-e2e-tdd-log.md) | 실제 주문 생성·확정 HTTP와 로컬 PG·Kafka, 별도 production streamer JVM/DB의 정상 저장·ack·동일 confirm 재요청 불변을 연결 검증. 당시 후속이었던 직접 발행 누락의 소비 DB 대조는 다음 기록 참조 |
| [W6 commit 뒤 발행 누락 전체 흐름 TDD 기록](docs/week6/pre-send-failure-e2e-tdd-log.md) | 정상 소비 양성 대조 뒤 별도 주문의 commit을 독립 DB 연결로 확인하고 send 직전 실패를 주입. 확정·재고·포인트 보존, 대상 broker record·구매 활동 0, confirm/callback/reconcile의 추가 PG 생성·발행 0을 검증. 당시 후속이었던 고정 Clock·재현 준비는 다음 기록 참조 |
| [W6 고정 Clock·W7 두 사례 재현 가이드](docs/week6/fixed-clock-w7-reproduction.md) | 주문 결제의 저장 시각에 전용 Clock을 주입하고 정상 소비·발행 누락 두 사례를 같은 고정 시각으로 재검증. 기존 W6 구현 commit `dd86f49`와 동일 코드의 2026-10-30 재실행·실제 사건 값·정리를 연결해 C09 완료. 최종 대조·제출은 후속 |
| [W6 after-commit 발행 TDD 기록](docs/week6/order-confirmed-publication-tdd-log.md) | 공통 최초 확정·AFTER_COMMIT·Kafka callback 관찰의 신규 23개, 관련 47개 스위트·862개와 API ArchUnit/Checkstyle 검증. 실제 격리 PG HTTP·DB·broker 증거이며 소비 DB·offset 재전달·W7 두 사례 완료와 구분 |
| [W6 공통 도메인 이벤트 발행 TDD 기록](docs/week6/common-domain-event-publication-tdd-log.md) | 주문 전용 listener·publisher를 순수 DomainEvent/발행 포트·공통 AFTER_COMMIT listener·Kafka publisher·명시적 타입별 route로 일반화한 TDD 증분. 기존 주문 wire·실패 경계·고정 Clock 회귀와 양 앱 ArchUnit·Checkstyle의 실제 검사 범위를 기록. 선행 실행 기록과 구분하며 사용자 DB·커밋은 제외 |
| [W5 채점 기준](docs/week5/grading-criteria.md) | 이전 주 테스트 PG 결제 교체·결과 복구 과제의 9개 평가 항목과 유지할 회귀 기준 |
| [W5 과제 원문](docs/week5/assignment-original.md) | 2026-10-18 제공된 구현·검증·제출 요구 원문 보존 |
| [W5 학습 자료 원문](docs/week5/learning-original.md) | 외부 결과의 불확실성·멱등성·트랜잭션·회복 전략의 개념 근거 |
| [W5 예약 상태 TDD 실행 기록](docs/week5/order-reservation-state-tdd-log.md) | 예약 상태 전이 12개 조합의 첫 Red·Green·Refactor 증거. 순수 enum만 구현했으며 영속화·실제 재고·PG·동시성은 미완료 |
| [W5 결제 시도 상태 TDD 실행 기록](docs/week5/payment-attempt-state-tdd-log.md) | PaymentAttempt 순수 모델의 발송·관찰·종결 보존·입력 경계 81개 및 당시 관련 회귀 151개 검증. 이후 DB 연결은 다음 기록 참조 |
| [W5 주문·예약·DB 연결 TDD 실행 기록](docs/week5/order-payment-persistence-tdd-log.md) | 주문 상태·결제수단 null 경계, 품목 예약·시도 JPA, 009/010 이행·POINT 보존·실제 저장 뒤 롤백 검증. 실제 재고 효과·서비스 TX A/B·PG 연결은 후속 범위 |
| [W5 로컬 테스트 PG·HTTP TDD 실행 기록](docs/week5/local-test-pg-http-tdd-log.md) | JDK HTTP fixture·Apache HttpClient의 실제 생성/조회 계약, 엄격한 422·응답 유실·자동 재시도 금지·개별 timeout·자원 정리 검증. 당시 주문·재고·TX A/B·callback 연결과 HMAC 선택은 미포함. 후속 HMAC 계산 단위 구현·DB/API 미연결 상태는 정책 문서와 HMAC 기록 참조. 과제 해석 질문은 필요 시 선택적으로 확인하며 담당자 확인·승인은 구현의 필수 전제가 아님 |
| [W5 재고 확보·반환 TDD 실행 기록](docs/week5/order-stock-reservation-tdd-log.md) | 내부 신규 준비 TX A·결과 적용 TX B, 실제 재고·HELD·삭제 후 반환·관리자 반환 여유·목록 버전의 연결 증분. 실제 검사 상태와 PG HTTP/고객 confirm 미연결 범위를 구분 |
| [W5 PG HTTP·주문·재고 연결 TDD 실행 기록](docs/week5/order-payment-http-connection-tdd-log.md) | 내부 신규 startNew·단건 refreshOnce의 실제 HTTP와 TX A/B 연결, 외부 TX 거절·예약/거래 수·롤백·복구 검증. 고객 confirm/reconcile·callback·180초 제한 조회·회로는 후속. 당시 미완료였던 HMAC 계산·지문 저장은 아래 후속 기록 참조. 과제 해석 질문은 필요 시 선택적으로 확인하며 담당자 확인·승인은 구현의 필수 전제가 아님 |
| [W5 HMAC 계산·비교 TDD 실행 기록](docs/week5/payment-request-fingerprint-tdd-log.md) | canonical v1·HMAC-SHA256·전체 지문 비교의 순수 단위 54개, 관련 회귀·ArchUnit 포함 343개와 Checkstyle main/test 검증. 당시 지문 DB 저장·키 설정·TX A·고객 API는 미연결 |
| [W5 HMAC 지문·버전 저장 TDD 실행 기록](docs/week5/payment-request-fingerprint-persistence-tdd-log.md) | PaymentAttempt 불변 지문·두 버전, 011 추가 이행·기존 PG 전체 NULL과 POINT 보존, 전용 MySQL 저장·유일 제약 경합·실제 SQL 후 rollback. 사용자 DB 미접근·미적용. 당시 후속이었던 키 공급·내부 서비스 연결은 다음 기록 참조, 고객 HTTP 연결은 미완료 |
| [W5 HMAC 환경변수 키 설정 TDD 실행 기록](docs/week5/payment-fingerprint-key-configuration-tdd-log.md) | 환경변수 현재/과거 키 공급·버전 선택·Spring bean, 잘못된 설정의 비교 불가·비노출. DB 없는 신규 92개와 관련 회귀·ArchUnit·Checkstyle 검증. 당시 실제 비밀키 설정·DB 이행·서비스 TX A·고객 API 연결은 미수행 |
| [W5 HMAC 주문 처리 연결 TDD 실행 기록](docs/week5/order-payment-fingerprint-connection-tdd-log.md) | 신규 TX A 지문 공동 저장·동일 재요청 비교·전역 UUID 충돌·실제 SQL 후 rollback·키 불가와 카드 없는 복구. Testcontainers/로컬 HTTP의 신규 44개·관련 798개·ArchUnit·Checkstyle 통과. 사용자 DB·실제 키 미변경, 고객 PG HTTP·callback·시간/회로는 후속 |
| [W5 고객 결제 계약 TDD 기록](docs/week5/customer-payment-contract-tdd-log.md) | 사용자 선택 JSON·오류3종·PG 주문 ID의 DB 없는 경계 구현. 신규126개·관련522개·ArchUnit·Checkstyle 통과. 실제 고객 controller PG 전환·조회 서비스·180초/회로·callback/reconcile은 후속 |
| [W5 고객 PG API·제한 조회 TDD 기록](docs/week5/customer-payment-api-tdd-log.md) | 고객 confirm·고객/관리자 조회의 PG 연결, 요청 진입 기준 제한 조회와 Resilience4j 조회 회로, 신규 PG 포인트 차감 0·과거 POINT 보존. callback/reconcile HTTP와 실제 180초 하드 상한 검증은 후속이며 사용자 DB는 접근하지 않음 |
| [W5 재확인·callback HTTP TDD 기록](docs/week5/payment-recovery-api-tdd-log.md) | 위임받은 복구 계약 선택·두 HTTP 진입점·권위 조회·공통 TX B·중복/경합/실패 후 복구의 최신 증거. 선행 전체 검사와 이번 관련 검사 범위를 구분하며 사용자 DB는 접근하지 않음 |
| [W4 채점 기준](docs/week4/grading-criteria.md) | 이전 주 상품 조회·인덱스·Redis 캐시 과제의 8개 평가 항목과 회귀 참고 |
| [W4 과제 원문](docs/week4/assignment-original.md) | 2026-10-12 제공된 채점 기준 원문 보존 |
| [전체 설계](docs/week2/commerce-erd-draft.md) | 확정 업무 규칙, 버드뷰, 계층 의존, ERD·도메인 책임, 세 대표 흐름 |
| [API 계약](docs/week2/commerce-api-contract.md) | 공통 입력·응답, 고객·관리자 API, 필터·페이지·정렬, 오류 계약 |
| [정책 선택과 설계 검토](docs/week2/commerce-policy-decisions.md) | 확정 정책·남은 제안과 설계 대안·선택 이유 |
| [TDD 계획](docs/week2/commerce-tdd-plan.md) | 테스트 기대값, Red·Green·Refactor, 구현 순서와 검증 기록 방법 |
| [전체 완료 체크리스트](docs/week2/commerce-completion-checklist.md) | 25개 API·API-01~24·동시성·운영 DDL의 완료 증거 추적 |
| [재고 TDD 실행 기록](docs/week2/commerce-tdd-stock-log.md) | 실제 구현 범위, Red·Green·Refactor 결과와 재검증 이력 |
| [사용자 식별 TDD 실행 기록](docs/week2/commerce-tdd-user-identity-log.md) | 식별·권한 공통 기능의 구현 결과와 fixture·HTTP 연결의 남은 범위 |
| [브랜드 이름 TDD 실행 기록](docs/week2/commerce-tdd-brand-name-log.md) | 이름 정리·필수값·코드 포인트 길이 규칙의 구현과 검증 결과 |
| [브랜드 모델 TDD 실행 기록](docs/week2/commerce-tdd-brand-log.md) | 생성·이름 변경·삭제 상태·상품 존재에 따른 삭제 조건과 후속 구현 범위 |
| [브랜드 영속성 TDD 실행 기록](docs/week2/commerce-tdd-brand-persistence-log.md) | 실제 MySQL 저장·조회, Unicode 경계·롤백·감사 시각과 남은 유스케이스 범위 |
| [브랜드 조회 TDD 실행 기록](docs/week2/commerce-tdd-brand-query-log.md) | 고객 상세 조회 application의 정상·없음·삭제 처리와 DB 상태 보존 |
| [브랜드 등록 TDD 실행 기록](docs/week2/commerce-tdd-brand-registration-log.md) | 관리자 신규 등록 application의 권한 우선·이름 검증·실제 저장 |
| [관리자 브랜드 조회 TDD 실행 기록](docs/week2/commerce-tdd-admin-brand-query-log.md) | 관리자 상세 application의 삭제 행 포함·권한 우선·상태 보존 |
| [브랜드 HTTP TDD 실행 기록](docs/week2/commerce-tdd-brand-http-log.md) | C01 공개 조회·응답·오류·입력 경계와 기존 API 회귀 |
| [주문 수량 TDD 실행 기록](docs/week2/commerce-tdd-order-quantity-log.md) | 같은 상품의 수량 합산·정수 경계·불변 결과와 후속 주문 연결 |
| [스키마 적용 안내](docs/week2/commerce-schema-operations.md) | 현재 제공하는 수동 SQL의 적용 방법·범위·실행 설정 |
| [브랜드 스키마 TDD 실행 기록](docs/week2/commerce-tdd-brand-schema-log.md) | 자동 생성 없이 실제 SQL·JPA 매핑·문자셋·시각·DB 제약 검증 |
| [관리자 브랜드 HTTP TDD 실행 기록](docs/week2/commerce-tdd-admin-brand-http-log.md) | 관리자 CRUD·권한 우선·공통 오류 처리와 브랜드 변경 경합 |
| [상품 TDD 실행 기록](docs/week2/commerce-tdd-product-log.md) | 상품 JPA·관리자 변경·공개 조회·좋아요 집계·정렬·페이지·입력 경계 |
| [포인트·좋아요 TDD 실행 기록](docs/week2/commerce-tdd-point-like-log.md) | 사용자 DB 초기화·충전·현재 관계·본인 목록·동시 중복과 롤백 |
| [주문 TDD 실행 기록](docs/week2/commerce-tdd-order-log.md) | 생성 스냅샷·본인/관리자 조회·확정·재요청·동시성·실제 SQL 후 롤백 |
| [전체 스키마 TDD 실행 기록](docs/week2/commerce-tdd-schema-log.md) | 수동 SQL 6개 적용·JPA validate·FK·유일/수치 제약·전체 관계 저장 |
| [관리자 Security 보완 기록](docs/week2/commerce-admin-security-log.md) | 과제 지원 설정·MockMvc 역할·CSRF·고객 fixture 분리와 기존 관리자 회귀 |
| [전체 모듈 Checkstyle 보완 기록](docs/week2/commerce-checkstyle-scope-log.md) | Java main/test/testFixtures 검사 연결과 규칙 위반 수정·검증 |

기능 작업 시 전체 설계와 해당 기능의 API 계약·정책 선택·TDD 기대값을 함께 확인한다. 제안이나 미정 정책은 문서 분리만으로 확정되지 않는다.

## W8 작업 기준

멘토 추가 공지에 따라 BASIC 세마포어는 처리량 제한 실험으로 다룰 수 있다. 이 프로젝트의 고객 발급은 [전체 설계](docs/week2/commerce-erd-draft.md#w8-쿠폰-예약과-발급)와 [테스트 기대값](docs/week2/commerce-tdd-plan.md#w8-쿠폰-예약과-복구-테스트)을 따른다. 원문 W8-C01~09의 번호와 문구는 보존하고, 선택 설계의 구현·실행 근거는 따로 기록한다. W7까지의 상품·주문·PG·Outbox·구매 활동을 유지한다.

고객에게 “한 장 확보”를 알리는 기준은 선별된 사용자의 몫과 발급 대기 기록이 함께 MySQL에 커밋됐다는 확인이다. 정상 마감 시 미배정 신규 요청은 Redis에서 종료한다. 작업자 자리가 모두 사용 중이어도 커밋된 예약은 유지한다. 서버·Redis 중단이나 응답 유실 뒤에도 작업을 재개할 때 MySQL의 예약을 읽어 고객 재신청 없이 한 장만 발급한다. 예약 커밋 전 Redis 선별 기록까지 유실되면 해당 고객의 몫을 복원할 근거가 없다.

### 11월 8일 BASIC 실험 참고

아래 동기 발급 경로와 429 기대값은 원문 샘플을 재현할 때 적용한다. 선택 설계에서는 신청 단계와 발급 작업자의 세마포어 획득 시점이 다르다.

쿠폰 총 발급 한도는 DB가, 신규 발급 경로 전체의 현재 처리 자리 C는 Redis가 보호한다. 사용자당 같은 캠페인 한 장, issuedCount <= totalQuota, 캠페인 조건·중복 재검사·발급 수 증가·보유 쿠폰 insert의 같은 TX를 지킨다. 사용자·캠페인 유일 제약을 유지하며 사전 조회만으로 동시 중복을 해결했다고 하지 않는다. 충돌 예외를 복구한다면 실패 TX rollback 뒤 새 TX에서 기존 결과를 확인하며 연결 오류·다른 제약 오류를 중복 성공으로 바꾸지 않는다. 자리 반환으로 issuedCount를 감소시키거나 이미 발급한 쿠폰을 취소하지 않는다.

POST /api/v1/coupon-campaigns/{campaignId}/issues와 GET /api/v1/coupons에 기존 사용자 식별을 적용한다. 최초 발급은 201, 같은 사용자·캠페인의 기존 결과는 200과 같은 couponId다. 기존 결과는 품절·비활성 뒤에도 보존하고 DB가 정상이면 Redis 장애 중에도 조회할 수 있다. 처리 자리 없음 429와 수용 상태 확인 불가 503을 업무 거절과 구분한다. 다른 사용자 발급·조회나 향후 발급 보장을 허용하지 않는다. 품절·비활성의 구체 오류 코드/상태, 응답 구조·정렬, 커밋 뒤 반환 실패의 응답 등 남은 선택은 구현 전에 정한다.

기존 쿠폰 조회 → 없으면 Redis 자리 획득 → 수용이 확인된 실행만 DB 발급 TX → TX·발급 실행 종료 확인 → 자기 token 반환 순서를 지킨다. Redis Set에는 서버가 실행마다 생성한 고유 token을 저장하며 사용자 ID·couponId·고객 요청 키와 구분한다. 같은 token 확인·크기 확인·추가는 한 Lua 실행으로 묶는다. 여러 HTTP 실행이 token을 공유하지 않고 같은 실행 안의 재확인만 허용한다. 반환은 자기 token만 제거하며 전체 Set 삭제·무조건 카운터 감소로 다른 자리를 지우지 않는다. 실행 수와 업무 효과 수를 혼동하지 않는다.

수용은 qualifier redisTemplateMaster의 실제 선언 타입과 String serializer에 맞춰 Spring Data Redis script API로 실행한다. 기본 REPLICA_PREFERRED의 count를 승인 근거로 삼거나 unchecked cast로 연결 오류를 숨기지 않는다. 현재 master endpoint와 유한 command timeout을 확인하고 Lua 결과 1/0을 예외·timeout과 구분한다. 입력·키 타입 검증을 먼저 하며 Lua의 원자성을 오류 시 전체 rollback으로 해석하지 않는다. 전용 공유 키·C는 신뢰하는 서버 설정이고 HTTP 입력으로 받지 않는다.

Redis 접근 실패·수용 응답 불확실에는 신규 DB 발급을 시작하지 않는다. 로컬 수용은 기본 닫힘·명시적 열기로 두고, 장애·상태 유실을 확인하면 닫은 뒤 PONG만으로 자동 재개하지 않는다. 모든 해당 발급 실행·DB 작업 종료와 실제 쿠폰 결과를 확인한 뒤 자기 범위의 비활성 token만 정리하고 재개한다. 종료가 불명확하면 닫힘을 유지한다. HTTP timeout·client 연결 종료는 작업 종료가 아니며 token TTL로 살아 있는 실행의 자리를 자동 회수하지 않는다. 반환 실패에도 확정 쿠폰은 보존한다. W4 캐시처럼 DB·local count·무제한 queue로 수용을 우회하지 않는다.

실제 Redis C=3 실험은 발급 한도를 충분히 두고 획득 직후·DB TX 전 barrier에서 세 실행을 유지한 뒤 네 번째 429·DB 발급 진입 0을 확인한다. 한 실행의 commit·종료·반환 뒤 새 발급과 최종 token 0을 확인한다. DB 총량 경쟁은 Redis 용량을 충분히 두거나 DB handler에 직접 진입시켜 별도로 검증하며 Redis 거절을 품절로 세지 않는다. 실제 SQL 후 rollback·동시 중복·응답 유실·실제 대상 Redis 중단·반환 실패/재개를 검증한다. mock·즉시 완료한 요청 수·PONG을 실제 수용/복구 증거로 대신하지 않는다. deadline과 소유 자원 정리를 유지한다.

### 공통 범위

W8은 발급과 본인 보유 목록을 다룬다. 쿠폰 사용·할인·환불·알림·전체 캠페인 CRUD는 범위 밖이다. W1 할인 설계를 W8 구현 완료나 새 사용 정책으로 이어받지 않으며 W7 주문 이벤트·구매 활동을 쿠폰에 불필요하게 연결하지 않는다. 감지하지 못한 Redis 상태 손실·다중 인스턴스 자동 장애 전환까지 안전한 분산 semaphore라고 주장하지 않는다. 자신의 격리 테스트 자원만 정리하며 사용자 DB·공유 cache·기존 topic/group·컨테이너·볼륨을 일괄 삭제하지 않는다. 미정 제품 정책은 먼저 질문한다.

## W7까지의 구현에서 유지할 경계

최초 PG 성공 반영·주문 확정·예약 소비와 Outbox insert는 같은 데이터 소스의 같은 로컬 TX에서 함께 commit/rollback한다. serialization 실패도 전파하며 Outbox만 REQUIRES_NEW 또는 after-commit insert로 분리하지 않는다. rollback 시 기존 미완료 주문·시도·예약은 TX 직전 상태로 남는다. 외부 PG SUCCESS가 남아 있으면 같은 거래 재조회로 결과 적용을 다시 시도하고 새 결제를 만들지 않는다. W7 주문 경로에서 기존 직접 send와 relay를 의도치 않게 병행하지 않는다.

Outbox는 W6의 stable eventId·orderId key·최초 confirmedAt·품목과 불변 payload를 저장한다. 단일 relay는 DB TX 밖에서 저장 사실을 보내고 producer future의 broker 성공 확인 뒤 별도 상태 TX에서 PUBLISHED로 바꾼다. 실패·timeout·broker 성공 후 상태 저장 실패는 같은 사건의 재시도 대상으로 유지한다. 늦은 callback이 새 시도의 상태를 임의 변경하지 않게 한다. PUBLISHED와 소비 완료, 시도 횟수와 실제 broker record 수, W6의 같은 offset 재전달과 W7의 같은 eventId 재발행을 구분한다. 소비 완료 기록·전체 품목 공동 TX·commit 뒤 ack·중복/충돌 판정은 유지한다.

W6 두 사례는 보존한 W6 테스트 구성 또는 revision 실행물로 실제 직접 발행 경로를 새 격리 실행에서 준비한다. 구현 revision `dd86f49`는 그 근거이며 특정 준비 방식만 강제하지 않는다. 실제 원본 DB를 보존했다면 보존 근거·사건 사실을 확인하고 준비 재현을 생략할 수 있다. 준비부터 복구 완료까지 같은 DB·broker·PG 상태를 유지하고 원본 사실의 eventId·confirmedAt·품목으로 없는 Outbox만 추가한다. 기존 행은 일치를 확인하고 부족한 사실·충돌을 추측하지 않는다. 준비와 복구의 PG 호출 기준을 분리하고 복구 중 create·조회·callback 추가 호출, 주문 재확정·예약 추가 소비는 모두 없어야 한다. SUCCESS 직접 insert·W7 Outbox 삭제·중간 정리·제품 운영용 우회 스위치로 누락을 꾸미지 않는다. W6 revision 연결은 완료된 기반이며 W7 복구 완료로 승계하지 않는다.

W7에서 구현한 Outbox·단일 polling relay와 W5의 포인트 결제 분리·PG 복구·TX/락·과거 POINT·소유권·삭제 비노출, W6 소비 중복 방지·실패 정책은 유지한다. 다중 relay·CDC 플랫폼·범용 saga·새 결제수단·랭킹 API·공개 복구 관리자 API로 확대하지 않는다. 미발행·격리 행은 자동 삭제하지 않고 기존 재개·보존·단일 실행의 한계를 유지한다. W7 과거 실행 기록을 다시 쓸 필요 없이 영향받은 범위의 실제 회귀 결과를 별도로 남긴다.

## 설계 문서 작성 지침

실제 설계는 [커머스 ERD 초안](docs/week2/commerce-erd-draft.md)을 바탕으로 확장하고, 아래 네 항목은 해당 전체 설계 문서에 함께 유지한다. API 상세 계약·정책 선택·TDD 계획은 위 참고 문서에 나누어 관리한다.

| 표현할 것 | 반드시 담을 내용 |
| --- | --- |
| 버드뷰 | 고객·관리자, API 서버, DB와 로컬 테스트 PG의 관계, 생성·조회·callback의 요청 방향. W7의 API 확정·Outbox 공동 DB TX → 단일 relay → Kafka → commerce-streamer → 구매 활동 DB와 두 중단 경계를 유지. W8은 Redis 선별 → MySQL 예약 커밋 → 확보 응답, 서버 작업자의 자리 획득 → DB 발급 커밋 → 자리 반환을 표시한다. GET 본인 목록은 DB 조회 경로로 표현한다. |
| 구조와 의존 | `interfaces` / `application` / `domain` / `infrastructure`의 역할과 허용하는 코드 의존 방향 |
| 도메인 관계 | `Brand–Product`, `User–Like–Product`, `Order–OrderItem`과 재고·포인트 책임. W5 결제 시도·예약, W6 확정 이벤트·소비 완료·활동, W7 Outbox의 불변 key/payload·상태·원자성을 유지. W8은 캠페인–사용자–예약–보유 쿠폰의 한도·유일성과 작업자 실행 token의 수명·책임을 표현 |
| 대표 흐름 | 관리자 변경 → 고객 조회, 좋아요 등록·취소, 주문 결제의 **기존 세 가지 흐름 모두** 유지. W2 POINT와 W6 직접 발행은 과거 흐름이며 W7은 PG 결과 → 확정·예약·Outbox 공동 commit → relay 발행 뒤 broker 확인·PUBLISHED 저장과 consumer의 구매 활동·ack를 구분. 두 완료의 선후는 보장하지 않음. W8 쿠폰 발급·본인 목록·완료 재요청·장애 후 재개 흐름은 별도로 추가. 포인트 충전·조회도 독립 유지 |

- 초안의 확정된 업무 규칙을 유지하고, 설계 제안과 미정 정책은 구분하여 표시한다. 제안이나 미정 정책을 임의로 확정하지 않는다.
- 각 흐름에는 요청 주체, 처리 순서, 상태·데이터 변경, 성공·실패 결과와 책임 주체를 드러낸다.
- 버드뷰의 요청 방향, 계층 간 코드 의존 방향, 실행 시 호출 순서를 혼동하지 않도록 구분한다.
- 포인트 충전 흐름만을 근거로 외부 결제 연동이나 이력 테이블을 추가하지 않는다. W5에서 명시한 로컬 테스트 PG 교체는 해당 과제 범위로 다루며, 실제 PG·환불·정산 등으로 확대하지 않는다. 미정 사항은 먼저 질문한다.
- W7 계약·Outbox/relay/소비 DB 경계는 기존 전체 설계에 확장하고 API·정책·TDD 문서와 연결한다. 주문·Outbox의 로컬 원자성과 Kafka 발행, broker 확인과 PUBLISHED 저장 및 소비 완료, 재발행과 동일 offset 재전달을 구분한다. 원문의 테이블·테스트 이름과 재시도 수치·선택적 BLOCKED를 유일한 정답으로 강제하지 않는다. 전체 설계 확장은 후속 구현 선택과 실제 변경에 맞춰 수행한다.
- W8 상세 설계는 예약 확보와 실제 발급의 커밋 시점, 총량·동시 실행 수·중복 처리, 정상 마감·장애 중 결과 불확실·반환 실패를 다룬다. BASIC 실험의 네 번째 429와 제품의 네 번째 예약 후 재신청 없는 발급은 별도 기대값으로 남긴다. 운영 수치·미정 응답은 임의 확정하지 않는다.
- 설계한 내용과 실제 구현된 내용을 구분한다. 문서 작성 요청만으로 코드를 구현하지 않는다.
- 관계와 흐름은 표·다이어그램으로 읽기 쉽게 표현하고, 같은 내용을 불필요하게 반복하지 않는다.
