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

## 설계 문서 작성 지침

실제 설계는 [커머스 ERD 초안](docs/week2/commerce-erd-draft.md)을 바탕으로 확장하고, 아래 네 항목은 해당 전체 설계 문서에 함께 유지한다. API 상세 계약·정책 선택·TDD 계획은 위 참고 문서에 나누어 관리한다.

| 표현할 것 | 반드시 담을 내용 |
| --- | --- |
| 버드뷰 | 고객·관리자, API 서버, DB의 관계와 요청 방향 |
| 구조와 의존 | `interfaces` / `application` / `domain` / `infrastructure`의 역할과 허용하는 코드 의존 방향 |
| 도메인 관계 | `Brand–Product`, `User–Like–Product`, `Order–OrderItem`의 관계와 재고·포인트의 책임 |
| 대표 흐름 | 관리자 변경 → 고객 조회, 좋아요 등록·취소, 포인트 충전 → 주문 확정의 **세 가지 흐름 모두** |

- 초안의 확정된 업무 규칙을 유지하고, 설계 제안과 미정 정책은 구분하여 표시한다. 제안이나 미정 정책을 임의로 확정하지 않는다.
- 각 흐름에는 요청 주체, 처리 순서, 상태·데이터 변경, 성공·실패 결과와 책임 주체를 드러낸다.
- 버드뷰의 요청 방향, 계층 간 코드 의존 방향, 실행 시 호출 순서를 혼동하지 않도록 구분한다.
- 포인트 충전 흐름을 작성한다는 이유만으로 외부 결제 연동이나 이력 테이블을 추가하지 않는다. 미정 사항은 먼저 질문한다.
- 설계한 내용과 실제 구현된 내용을 구분한다. 문서 작성 요청만으로 코드를 구현하지 않는다.
- 관계와 흐름은 표·다이어그램으로 읽기 쉽게 표현하고, 같은 내용을 불필요하게 반복하지 않는다.
