# 시스템 설계

이 문서는 `commerce-api` 구현의 기준이 되는 API 계약과 설계 원칙을 정의한다. 구현은 이 문서에 명시된 계약과 도메인 규칙을 따르며, 외부 동작에 영향을 주지 않는 세부사항은 구조와 의존성 원칙 안에서 결정한다. 문서에 없는 기능은 추가하지 않고, 구현에 필요한 외부 정책이 정해지지 않았으며 적용할 잠정 기준도 없다면 확인 후 반영한다. `[잠정]` 정책은 기획 결정에 따라 변경될 수 있지만, 결정 전까지는 문서에 적힌 현재 값을 구현과 테스트의 기준으로 사용한다.

2주차 설계 원본인 [`docs/week2/system-design.md`](../week2/system-design.md)를 기반으로 생성했으며, 이후 주차에서 발생하는 설계 변경은 이 문서 세트에 반영한다. 2주차 당시의 설계 원본은 스냅샷으로 보존하고 수정하지 않는다.

2026-10-06 브랜드 일괄 삭제에 이어 주문 확정·동시성 설계를 확정했다. Order·Point·Product의 비관적 잠금, 공통 변경 경로, 쿼리 선택 기준과 검증 기준·완료 근거는 [4.3](./sections/04-use-cases.md#43-주문-확정)에, 선택 이유와 비용은 [부록 A.3](./sections/appendix-decisions.md#a3-주문-확정의-포인트재고-처리-순서)에 둔다. 설계 문서 세트만으로 구현 기준을 확인할 수 있으며 별도 검토 노트에 의존하지 않는다.

2026-10-07 브랜드 일괄 삭제의 구현·검증을 완료했다. infrastructure의 native SQL bulk와 Brand 삭제를 하나의 트랜잭션에서 처리하며, 실제 bulk 이후 실패의 rollback, 삭제 후 사용 제한·과거 주문 보존·HTTP 접근 경계를 검증했다. 브랜드 인계 시점의 전체 검사 결과는 326건·실패/오류/skip 0이며 Checkstyle·ArchUnit도 통과했다. 브랜드 bulk의 인덱스 실행계획 확인 항목은 사용자 결정에 따라 실제 데이터의 bulk 동작 테스트, 단독 인덱스 생성·중복 없음, 빈 테이블 EXPLAIN을 근거로 인정했다. 데이터가 있는 조건의 실행계획·탐색 효율·성능과 브랜드 삭제의 동시 경쟁은 미검증이다. 삭제 Brand 아래 미삭제 Product가 남는 경우, 조회에서는 숨겨지지만 상품 ID 기반 명령에서 사용될 가능성은 남아 있다([4.1.4](./sections/04-use-cases.md#414-삭제-이후-사용-제한과-조회-방어)·[4.1.5](./sections/04-use-cases.md#415-동시성-범위와-향후-대안)). 실행계획 확인 범위와 한계는 [4.1.6](./sections/04-use-cases.md#416-인덱스와-설계-재검토-기준), 검증 기준과 완료 근거는 [4.1.7](./sections/04-use-cases.md#417-검증-계획)에 둔다.

2026-10-07 주문 확정·동시성의 구현·검증을 완료했다. `Order → Point → Product` 순서의 비관적 쓰기 잠금을 적용하고, 포인트 충전과 상품 수정·삭제·최종 재고 설정도 같은 행 잠금에 참여한다. 최종 `cleanTest check`의 저장된 테스트 결과는 353건·실패/오류/skip 0이며 ArchUnit 3건이 통과했다. Checkstyle은 직전 검사 통과 이후 Java 소스 변경이 없어 최종 실행에서 UP-TO-DATE였다. 실제 SQL 이후 rollback, 경쟁 결과와 자원 정리의 완료 근거·미검증 한계는 [4.3.5](./sections/04-use-cases.md#435-구현-단계-검증-계획)에 둔다.

2026-10-15 고객 상세의 상품명·Brand 이름·가격·재고만 JSON String cache-aside로 저장하고 좋아요 수는 DB 조회로 합성하기로 했다. 기본정보 영향 쓰기는 공통 afterCommit callback에서 commit 뒤 동기 DEL한다. TTL은 시험 10초/평시 5분의 고정값이며 hit에서 연장하지 않는다. 평시/일반 회귀는 cache on·필요한 캐시 비교만 DB-only, cold는 기본 miss+동시 miss 관측이다. 기존 master·초기 connect/command 200ms 실측·실패 로그/재시도 없음·기존 로컬 MySQL/Redis·loopers 공유를 사용하고 CPU 수집은 모두 제외한다. 구현된 SQL/포트/TX 경계와 확인한 데이터/표본은 [4.4 상품 조회](./sections/04-use-cases.md#44-상품-조회)와 연결된 실행 기록을 따른다.

2026-10-15 상품 조회의 기능 구현·변경/실패/복구·회귀 검증을 완료했다. 목록 SQL은 유지하고 인덱스를 추가했으며(2026-10-16 라이팅 실험에서 고른 product `(brand_id, price)`·`(brand_id, created_at)`·`(brand_id, deleted_at)`, product_like `(product_id)`로 정리), 상세 기본정보 cache-aside와 영향 쓰기의 commit 뒤 DEL을 구현했다. 변경·rollback·DEL 실패·늦은 fill은 실제 Spring·MySQL·Redis 통합 테스트로, Redis 중단·재시작은 로컬 앱으로 확인했다. 최종 `cleanTest check`는 377건·fail/error/skip 0이다. 목록 전후 반복 지연·전체 응답 비교는 보완이 필요하고, 캐시의 전체 SQL 수·읽은 행 수 감소나 지연 향상은 입증되지 않았다. 글을 준비하며 SQL 실험과 남은 제출 근거를 보완한다. 2026-10-16 목록 전후 비교(E0)를 실제 API로 채웠고, 캐시 hit의 비싼 원본 호출 감소 조건은 미충족으로 판정해 정책을 유지했다. 구체 구성과 한계는 [4.4](./sections/04-use-cases.md#44-상품-조회)에 둔다.

## 문서 구성

|문서|담당 범위|
|---|---|
|[1. 버드뷰](./sections/01-overview.md)|시스템 개요, 구성과 기능 범위|
|[2. 구조와 의존](./sections/02-architecture.md)|패키지 구조, 레이어 역할, 의존 방향, Repository와 명명 규칙|
|[3. 도메인 관계](./sections/03-domain-model.md)|도메인 모델, 관계와 책임, 클래스 설계, 삭제 정책|
|[4. 대표 흐름](./sections/04-use-cases.md)|브랜드·상품 일괄 삭제, 포인트 충전·주문 확정, 상품 조회·상세 캐시의 장애 계약과 검증 기준·완료 근거|
|[5. API 계약과 주요 규칙](./sections/05-api-contract.md)|공통 계약, 고객·관리자 API, 테스트 기대값|
|[부록 A. 설계 대안과 선택 근거](./sections/appendix-decisions.md)|설계 대안 비교, 선택 이유와 비용|
