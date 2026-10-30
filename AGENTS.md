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
| [W6 채점 기준](docs/week6/grading-criteria.md) | 이번 주 주문 확정 이벤트·Kafka·상품 구매 활동 과제의 11개 평가 항목, 필수 실험·W7 인계 증거·제외 범위 |
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

현재 채점은 W6 원문을 우선한다. W5의 PG 결제·예약·결과 복구에서 출발하여 최초 PG 확정 commit 뒤 OrderConfirmed를 Kafka로 직접 발행하고 commerce-streamer가 구매 활동을 저장하도록 확장한다. W5의 포인트 차감 분리·재요청·TX/락, 이전 POINT 사실·기존 오류·소유권·삭제 비노출 등 변경 대상이 아닌 계약은 보존한다. 기존 통과 기록을 W6 충족으로 이어받지 않으며, 새 11개 항목의 코드·실제 broker/DB·실행 증거를 따로 확인한다. 채점 기준 저장만으로 기능 구현·설계 갱신이 완료된 것으로 보지 않는다.

W6에서는 stable eventId·orderId key·저장된 최초 confirmedAt/품목을 사용한다. 발행 실패로 확정 주문을 되돌리거나 PG 결제를 다시 만들지 않는다. 소비 완료 기록과 모든 품목의 구매 활동은 같은 DB TX로 보호하고 commit 성공 후 ack한다. 같은 사실의 재전달과 식별자/payload 충돌을 구분하며 메모리 중복 검사·producer idempotence만으로 DB 효과를 보호했다고 보지 않는다. 실제 offset 재전달과 실제 Spring listener 실패/재시작은 각각 검증한다.

Outbox·랭킹 API는 이번 구현 범위가 아니다. 직접 발행의 누락과 정상 소비 두 사례는 W6 직접 경로의 재현 코드·실제 사건 사실·사용 revision으로 남기고 테스트 자원은 정리한다. W7을 위해 임시 DB를 계속 켜두거나 Outbox 실행 뒤 행을 지워 누락을 꾸미지 않는다. 미정 구현 선택은 기존 원칙대로 질문하며 사용자 DB·다른 실행의 topic/group·볼륨을 삭제하지 않는다.

## 설계 문서 작성 지침

실제 설계는 [커머스 ERD 초안](docs/week2/commerce-erd-draft.md)을 바탕으로 확장하고, 아래 네 항목은 해당 전체 설계 문서에 함께 유지한다. API 상세 계약·정책 선택·TDD 계획은 위 참고 문서에 나누어 관리한다.

| 표현할 것 | 반드시 담을 내용 |
| --- | --- |
| 버드뷰 | 고객·관리자, API 서버, DB와 로컬 테스트 PG의 관계, 생성·조회·callback의 요청 방향. W6에서는 API 생산자 → Kafka → commerce-streamer → 구매 활동 DB와 발행/소비 실패 경계도 함께 표현 |
| 구조와 의존 | `interfaces` / `application` / `domain` / `infrastructure`의 역할과 허용하는 코드 의존 방향 |
| 도메인 관계 | `Brand–Product`, `User–Like–Product`, `Order–OrderItem`의 관계와 재고·포인트의 책임. W5 결제 시도·예약·외부 거래 식별과 W6 확정 이벤트·소비 완료·상품 구매 활동의 식별/유일성·원자성도 함께 표현 |
| 대표 흐름 | 관리자 변경 → 고객 조회, 좋아요 등록·취소, 주문 결제의 **세 가지 흐름 모두**. W2 POINT 결제는 과거 흐름으로 구분하고, W5 PG 시도 → 결과 확인 → 주문·예약 반영에 W6 최초 확정 commit → 직접 발행 → 구매 활동 저장 → ack를 확장. 포인트 충전·조회는 독립 유지 |

- 초안의 확정된 업무 규칙을 유지하고, 설계 제안과 미정 정책은 구분하여 표시한다. 제안이나 미정 정책을 임의로 확정하지 않는다.
- 각 흐름에는 요청 주체, 처리 순서, 상태·데이터 변경, 성공·실패 결과와 책임 주체를 드러낸다.
- 버드뷰의 요청 방향, 계층 간 코드 의존 방향, 실행 시 호출 순서를 혼동하지 않도록 구분한다.
- 포인트 충전 흐름만을 근거로 외부 결제 연동이나 이력 테이블을 추가하지 않는다. W5에서 명시한 로컬 테스트 PG 교체는 해당 과제 범위로 다루며, 실제 PG·환불·정산 등으로 확대하지 않는다. 미정 사항은 먼저 질문한다.
- W6 계약·전달/DB 경계는 기존 전체 설계에 확장하고 API·정책·TDD 문서와 연결한다. after-commit과 broker 발행의 원자성, broker 수신과 소비 DB 완료, position과 committed offset을 구분한다. 원문 예시의 테이블명·eventId 생성법·listener factory·error handler를 유일한 정답으로 강제하지 않는다.
- 설계한 내용과 실제 구현된 내용을 구분한다. 문서 작성 요청만으로 코드를 구현하지 않는다.
- 관계와 흐름은 표·다이어그램으로 읽기 쉽게 표현하고, 같은 내용을 불필요하게 반복하지 않는다.
