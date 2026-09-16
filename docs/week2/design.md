# 설계 문서 — 브랜드·상품·좋아요 / 포인트·주문 / 관리자

입력: 요구사항 정의서 (5장 정합성 검사 통과 상태)
작성 기준: 「설계 문서 작성 가이드」
진행 상태: **1~5장·부록 작성 완료.** 요구사항 정의서에 설계 피드백 반영 완료(requirements.md). 5-8 완료 조건은 코드 작성 후 검사한다.
사전 확정: 모든 테이블의 식별자는 Long 자동 증가, 공통 컬럼 `created_at`·`updated_at`·`deleted_at` (DR-01, DR-16)

---

## 1. 시스템 (Level 1)

### 1-1. 제약 입력

| CON-ID | 제약 | 근거 |
|---|---|---|
| CON-01 | API 경로·메서드는 원문 표에 적힌 것을 그대로 쓴다. 원문에 없는 추가 기능의 경로는 ASM-23에서 정한다 | 과제 조건 |
| CON-02 | 고객 API는 `/api/v1`, 관리자 API는 `/api-admin/v1`로 분리한다 | 과제 조건 |
| CON-03 | 고객 API·관리자 API 모두 요청자를 HTTP 헤더 `X-USER-ID`로 식별한다. 관리자 API도 같은 헤더의 값으로 관리자를 확정한다. 값의 형식·검증 방식·식별 실패의 세분화는 설계에서 정한다 (ASM-01, ASM-26) | 과제 조건 |
| CON-04 | 모든 테이블의 식별자는 Long 자동 증가이고, 공통 컬럼 `created_at`·`updated_at`·`deleted_at`을 가진다 | 팀 조건 (DR-01, DR-16으로 요구사항에 올림) |

### 1-2. 팀 고정 결정

가이드 1-2와 동일. 벗어나는 항목 없음. 여기에 더해 이번 프로젝트에서 확정한 것:

| 항목 | 결정 | 근거 |
|---|---|---|
| 식별자 형식 | 모든 테이블의 PK는 Long 자동 증가. `X-USER-ID` 헤더 값 = 사용자 PK. 경로·바디의 모든 ID도 같은 형식 | CON-04, DR-01 |
| 공통 컬럼 | 모든 테이블에 `created_at`, `updated_at`, `deleted_at` | CON-04, DR-16 |

### 1-3. 계약 공통 규약에서 벗어나거나 확정하는 항목

4-1에서 적는다. 1장 시점에 이미 드러난 것만 미리 표시한다.

| 항목 | 1-3 기본값 | 이 프로젝트 | 근거 |
|---|---|---|---|
| 버전 표기 | `/api/v1` | 고객 `/api/v1`, 관리자 `/api-admin/v1` | CON-02 |
| 인증 전달 | 헤더 (헤더명 확정 필요) | 헤더 `X-USER-ID`, 값은 사용자 PK(Long). 시스템이 넣는 값이라 형식 오류를 따로 구분하지 않는다 | CON-03, DR-01 |
| 식별자 | 내부 PK 노출 | 동일. Long 자동 증가 | DR-01 |
| 페이징 · 검증 에러 형식 | 확정 필요 | 4-1에서 확정 | — |

---

## 2. 컨텍스트 (Level 2)

### 2-1. 바운디드 컨텍스트

용어집 22개 단어의 배치: 사용자·관리자 → BC-01 / 브랜드·상품·가격·재고·좋아요·좋아요 수 → BC-02 / 포인트·잔액·충전·환불·운영자 충전/운영자 차감·표현 범위 → BC-03 / 주문·주문 품목·단가·항목 금액·합계·결제액·결제 결과 → BC-04

### BC-01 사용자 (user)

- 유형: 지원
- 책임: 요청자가 누구이며 관리자인지를 결정한다

#### 소유 개념
| 명사 | 이 컨텍스트 안에서의 정의 | 출처 |
|---|---|---|
| 사용자 | 요청자 식별의 단위. 관리자 권한 여부를 가진다. 잔액은 갖지 않는다(잔액의 정의는 BC-03) | 용어집 |
| 관리자 | 관리자 권한을 가진 사용자. 별개의 대상이 아니다 (ASM-26) | 용어집 |

#### 참조 개념
없음

#### 용어 충돌
없음

### BC-02 카탈로그 (catalog)

- 유형: 코어
- 책임: 고객에게 보이는 판매 대상을 결정한다

#### 소유 개념
| 명사 | 이 컨텍스트 안에서의 정의 | 출처 |
|---|---|---|
| 브랜드 | 상품이 속하는 단위. ACTIVE/DELETED 상태를 가진다 | 용어집 |
| 상품 | 하나의 브랜드에 속하며 현재 가격·재고·ACTIVE/DELETED 상태를 가진 판매 대상 | 용어집 |
| 가격 | 상품의 현재 값. 관리자가 바꿀 수 있다 | 용어집 |
| 재고 | 상품의 현재 수량. 관리자가 설정하고 주문 확정이 차감한다 (DR-03) | 용어집 |
| 좋아요 | 사용자–상품 관계. 같은 쌍에 하나 (DR-02) | 용어집 |
| 좋아요 수 | 한 상품에 대한 좋아요 관계의 개수. 저장값이 아닌 계산값 | 용어집 |

#### 참조 개념
| 명사 | 소유 컨텍스트 | 참조 이유 |
|---|---|---|
| 사용자 | BC-01 | 요청자 식별. 좋아요 관계의 한쪽 |

#### 용어 충돌
| 단어 | 여기서의 뜻 | 다른 컨텍스트에서의 뜻 |
|---|---|---|
| 상품 | 현재 가격·재고·삭제 상태를 가진 판매 대상 | BC-04 주문: 주문 시점 단가가 고정된 구매 대상(주문 품목이 가리키는 것) |

### BC-03 포인트 (point)

- 유형: 지원
- 책임: 사용자의 잔액을 결정한다

#### 소유 개념
| 명사 | 이 컨텍스트 안에서의 정의 | 출처 |
|---|---|---|
| 포인트 | 유일한 결제 수단. 1포인트 = 1원. 사용자마다 하나의 포인트 계정이 있다 | 용어집 |
| 잔액 | 사용자가 보유한 포인트의 저장값. 단일 값이며 충전 건·이력으로 나누지 않는다 (DR-07) | 용어집 |
| 충전 | 잔액에 `amount`를 더하는 행위 | 용어집 |
| 환불 | 잔액에서 `amount`를 빼는 행위. 돈의 이동 없음 | 용어집 |
| 운영자 충전 / 운영자 차감 | 관리자가 특정 사용자의 잔액에 `amount`를 더하거나 빼는 행위. 사유 없음 (ASM-25) | 용어집 |
| 표현 범위 | 금액을 저장·표현할 수 있는 정수 범위. 값은 3장에서 정한다 (ASM-05) | 용어집 |

#### 참조 개념
| 명사 | 소유 컨텍스트 | 참조 이유 |
|---|---|---|
| 사용자 | BC-01 | 잔액의 주인. 요청자 식별. 운영자 충전·차감의 대상 |

#### 용어 충돌
없음

### BC-04 주문 (order)

- 유형: 코어
- 책임: 주문의 확정 여부를 결정한다

#### 소유 개념
| 명사 | 이 컨텍스트 안에서의 정의 | 출처 |
|---|---|---|
| 주문 | 사용자가 만든 주문 품목의 묶음. DRAFT/CONFIRMED 상태, 합계, 결제액, 결제 결과를 가진다 | 용어집 |
| 주문 품목 | 주문 안의 한 줄. 상품 ID·수량·단가·항목 금액. 한 주문에 같은 상품은 하나 (ASM-11) | 용어집 |
| 단가 | 주문 생성 시점 상품 가격의 사본. 이후 가격 변경에 영향받지 않는다 (ASM-10) | 용어집 |
| 항목 금액 | 단가 × 수량 | 용어집 |
| 합계 | 항목 금액의 합 | 용어집 |
| 결제액 | 확정 시 잔액에서 차감된 금액 = 합계. 확정 후에만 존재 | 용어집 |
| 결제 결과 | 확정 시 차감이 이루어졌다는 기록. 필드는 3장 (ASM-13) | 용어집 |

#### 참조 개념
| 명사 | 소유 컨텍스트 | 참조 이유 |
|---|---|---|
| 사용자 | BC-01 | 요청자 식별. 주문의 주인(원문 "구매자", DR-09) |
| 상품 | BC-02 | 주문 품목의 대상. 생성 시 존재·삭제 여부·가격(단가 원천) 조회, 확정 시 존재·삭제 여부·재고 차감 |
| 잔액 | BC-03 | 확정 시 결제액 차감 |
| 표현 범위 | BC-03 | 합계의 상한 (ASM-05). 상수이므로 동기 호출은 없다 |

#### 용어 충돌
| 단어 | 여기서의 뜻 | 다른 컨텍스트에서의 뜻 |
|---|---|---|
| 상품 | 주문 시점 단가가 고정된 구매 대상. 주문 품목이 ID로 가리킨다 | BC-02 카탈로그: 현재 가격·재고·삭제 상태를 가진 판매 대상 |
| 사용자 | 주문을 만든 자(구매자) | BC-01: 식별과 권한의 주체 |

### 2-2. 불변식 배치와 애그리거트 후보

#### 2-2-A. 불변식 배치

| INV-ID | 대상 용어 (요구사항 3-1) | 소속 AG-ID | 위반 시 |
|---|---|---|---|
| INV-01 | 잔액 | AG-05 | 거부 (AG 내) |
| INV-02 | 잔액, 표현 범위 | AG-05 | 거부 (AG 내) |
| INV-03 | 재고 | AG-03 | 거부 (AG 내) |
| INV-04 | 좋아요 | AG-04 | 거부 (AG 내). 검증 위치는 DR-05 |
| INV-05 | 좋아요 수, 좋아요, 상품 | AG-04 | 거부 (AG 내). 좋아요 수를 저장하지 않고 관계 개수로만 조회하므로 정의로 성립한다 |
| INV-06 | 주문, 주문 품목, 단가, 항목 금액, 합계 | AG-06 | 거부 (AG 내) |
| INV-07 | 주문 품목 | AG-06 | 거부 (AG 내) |
| INV-08 | 주문, 주문 품목 | AG-06 | 거부 (AG 내) |
| INV-09 | 주문, 결제액, 합계 | AG-06 | 거부 (AG 내) |
| INV-10 | 상품, 브랜드 | AG-02, AG-03 | 거부 (FR 트랜잭션: FR-ADMIN-BRAND-05, FR-ADMIN-PRODUCT-02, DR-04). 동시 요청 창은 OQ-02 |
| INV-11 | 상품, 브랜드 | AG-03 | 거부 (AG 내). 상품이 브랜드 ID를 불변으로 들고, 수정 시 입력받지 않는다 (ASM-16) |
| INV-12 | 주문, 주문 품목 | AG-06 | 거부 (AG 내) |
| INV-13 | 상품, 가격 | AG-03 | 거부 (AG 내) |
| INV-14 | 브랜드 | AG-02 | 거부 (AG 내) |
| INV-15 | 사용자, 관리자 | AG-01 | 거부 (AG 내). 설계 피드백으로 요구사항 3-1에 추가, DR-06 |

#### 2-2-B. 애그리거트 후보

| AG-ID | BC-ID | 루트 | 포함 개념 | 근거 INV-ID | 상태 전이 (요구사항 3-2 용어) |
|---|---|---|---|---|---|
| AG-01 | BC-01 | 사용자 | 관리자 (권한 속성) | INV-15 (DR-06) | 없음 |
| AG-02 | BC-02 | 브랜드 | 없음 | INV-14, INV-10 | 브랜드 |
| AG-03 | BC-02 | 상품 | 가격, 재고 | INV-03, INV-11, INV-13, INV-10 | 상품 |
| AG-04 | BC-02 | 좋아요 | 없음 (좋아요 수는 관계 개수의 계산값, 저장 개념 없음) | INV-04, INV-05 | 없음 |
| AG-05 | BC-03 | 포인트 | 잔액 | INV-01, INV-02 | 없음 |
| AG-06 | BC-04 | 주문 | 주문 품목, 단가, 항목 금액, 합계, 결제액, 결제 결과 | INV-06, INV-07, INV-08, INV-09, INV-12 | 주문 |

메모
- 충전·환불·운영자 충전/차감은 AG-05 루트의 행위(메서드)이지 포함 개념이 아니다. 표현 범위는 BC-03의 값 정의이며 AG에 속하지 않는다.
- 브랜드와 상품을 별개 AG로 둔 이유와 재고를 상품 AG에 넣은 이유는 DR-04, DR-03.

### 2-3. 컨텍스트 맵

| 상류 BC | 하류 BC | 관계 유형 | 교환 개념 | 목적 |
|---|---|---|---|---|
| BC-01 사용자 | BC-02 카탈로그 | Conformist | 사용자 ID → 존재 여부·관리자 여부 | 조회 |
| BC-01 사용자 | BC-03 포인트 | Conformist | 사용자 ID → 존재 여부·관리자 여부 (요청자, 운영자 충전·차감 대상) | 조회 |
| BC-01 사용자 | BC-04 주문 | Conformist | 사용자 ID → 존재 여부·관리자 여부 | 조회 |
| BC-02 카탈로그 | BC-04 주문 | Conformist | 상품 ID → 존재·삭제 여부·현재 가격 | 조회 |
| BC-02 카탈로그 | BC-04 주문 | Conformist | 주문 품목 수량 → 재고 차감 | 변경 (FR-ORDER-02, 2-4 참조) |
| BC-03 포인트 | BC-04 주문 | Conformist | 합계 → 잔액 차감 | 변경 (FR-ORDER-02, 2-4 참조) |
| BC-03 포인트 | BC-04 주문 | Conformist | 표현 범위 → 합계 상한 (상수 공유, 호출 없음) | 조회 |

순환 없음. 방향은 BC-01 → {02, 03, 04}, BC-02 → 04, BC-03 → 04.

### 2-4. FR × 컨텍스트 매핑

BC-01 사용자는 모든 FR의 공통 사전 조건(요청자 식별·관리자 판정)으로 참여한다. 아래 표에서 "사용자"는 그 참여를 뜻하며, 그 외 목적이 있으면 근거에 적는다.

| FR-ID | 진입 BC | 참여 BC | 변경 AG-ID | 일관성 | 근거 |
|---|---|---|---|---|---|
| FR-BRAND-01 | BC-02 | 사용자 | 없음 | 즉시 | 조회 |
| FR-PRODUCT-01 | BC-02 | 사용자 | 없음 | 즉시 | 조회. 좋아요 수는 같은 BC의 AG-04 관계 개수 (DR-02) |
| FR-PRODUCT-02 | BC-02 | 사용자 | 없음 | 즉시 | 조회 |
| FR-LIKE-01 | BC-02 | 사용자 | AG-04 | 즉시 | 상품 존재·삭제 여부는 같은 BC의 AG-03 조회 |
| FR-LIKE-02 | BC-02 | 사용자 | AG-04 | 즉시 | 상품 존재 여부는 같은 BC의 AG-03 조회 |
| FR-LIKE-03 | BC-02 | 사용자 | 없음 | 즉시 | 조회 |
| FR-POINT-01 | BC-03 | 사용자 | AG-05 | 즉시 | |
| FR-POINT-02 | BC-03 | 사용자 | 없음 | 즉시 | 조회 |
| FR-POINT-03 | BC-03 | 사용자 | AG-05 | 즉시 | |
| FR-ORDER-01 | BC-04 | 사용자, 카탈로그 | AG-06 | 즉시 | 카탈로그는 상품 존재·삭제 여부·가격 조회(단가 스냅샷 원천). 재고·잔액 변화 없음 |
| FR-ORDER-02 | BC-04 | 사용자, 카탈로그, 포인트 | AG-03 (품목별 상품), AG-05, AG-06 | 즉시 (다중 AG) | ASM-14 전부 또는 전무. 근거 DR-08. 참여 BC 3개 → OQ-01. 실패 케이스는 전부 "유지"라 실패 경로 쓰기 없음 |
| FR-ORDER-03 | BC-04 | 사용자 | 없음 | 즉시 | 조회 |
| FR-ORDER-04 | BC-04 | 사용자 | 없음 | 즉시 | 조회 |
| FR-ADMIN-BRAND-01 | BC-02 | 사용자 | 없음 | 즉시 | 조회 |
| FR-ADMIN-BRAND-02 | BC-02 | 사용자 | AG-02 | 즉시 | |
| FR-ADMIN-BRAND-03 | BC-02 | 사용자 | 없음 | 즉시 | 조회 |
| FR-ADMIN-BRAND-04 | BC-02 | 사용자 | AG-02 | 즉시 | |
| FR-ADMIN-BRAND-05 | BC-02 | 사용자 | AG-02 | 즉시 | 같은 트랜잭션에서 AG-03(삭제되지 않은 소속 상품 유무) 조회로 INV-10 검증 (DR-04) |
| FR-ADMIN-PRODUCT-01 | BC-02 | 사용자 | 없음 | 즉시 | 조회 |
| FR-ADMIN-PRODUCT-02 | BC-02 | 사용자 | AG-03 | 즉시 | 같은 트랜잭션에서 AG-02(브랜드 존재·삭제 여부) 조회로 INV-10 검증 (DR-04) |
| FR-ADMIN-PRODUCT-03 | BC-02 | 사용자 | 없음 | 즉시 | 조회 |
| FR-ADMIN-PRODUCT-04 | BC-02 | 사용자 | AG-03 | 즉시 | |
| FR-ADMIN-PRODUCT-05 | BC-02 | 사용자 | AG-03 | 즉시 | 좋아요 관계(AG-04)·주문 품목(AG-06)은 건드리지 않는다 (ASM-07, 원문) |
| FR-ADMIN-PRODUCT-06 | BC-02 | 사용자 | AG-03 | 즉시 | |
| FR-ADMIN-ORDER-01 | BC-04 | 사용자 | 없음 | 즉시 | 조회. 구매자별 묶음은 주문이 가진 사용자 ID로 묶는다 |
| FR-ADMIN-ORDER-02 | BC-04 | 사용자 | 없음 | 즉시 | 조회 |
| FR-ADMIN-POINT-01 | BC-03 | 사용자 | AG-05 | 즉시 | 사용자 BC는 요청자 판정과 대상 사용자 존재 조회 두 목적 |
| FR-ADMIN-POINT-02 | BC-03 | 사용자 | AG-05 | 즉시 | 위와 같음 |

### 2-5. 완료 조건

- [x] 용어집 22개 단어가 각각 정확히 하나의 BC 소유 개념에 있다 (2-1 머리 배치표)
- [x] 출처 "설계 도입"인 소유 개념: 없음
- [x] 모든 참조 개념(사용자, 상품, 잔액, 표현 범위)의 소유 컨텍스트가 2-3에 상류로 있다
- [x] 모든 INV의 위반 시가 정해져 있다. 최종 일관성: 없음. 거부(FR 트랜잭션): INV-10, DR-04
- [ ] 거부(FR 트랜잭션)인 INV-10의 관련 FR이 2-4에서 "즉시 다중 AG"인가 — **아니다.** 두 FR 모두 변경 AG는 하나이고 다른 AG는 조회로만 검증한다. 가이드의 문자에서 벗어나며 DR-04에 기록. 동시성 창은 OQ-02
- [x] 모든 AG가 근거 INV를 가진다 (AG-01은 설계 피드백으로 추가된 INV-15, DR-06)
- [x] 요구사항 3-2의 상태 전이 용어(주문, 브랜드, 상품)가 모두 AG 루트다
- [x] 2-3에 순환이 없다 (DR-02로 카탈로그↔좋아요 순환 해소)
- [x] 2-3 "변경" 행 2개가 모두 2-4 FR-ORDER-02에 변경 AG와 일관성으로 적혀 있다
- [x] 요구사항 2절 FR 28개가 전부 2-4에 있고 진입 BC가 하나다
- [x] 즉시 다중 AG FR(FR-ORDER-02)에 근거가 있다 (DR-08)
- [x] 참여 BC 3개 이상 FR(FR-ORDER-02)이 OQ-01에 있다
- [x] 금지어 없음 (영문 식별자 user/catalog/point/order는 BC 식별자로 허용)

체크 안 된 항목 하나(INV-10)는 DR-04와 OQ-02로 처리하고 넘어간다 (동시성을 이번 범위에서 다루지 않기로 결정).

3장으로 인계되는 것
- 소유 개념 → 테이블 후보: 사용자, 브랜드, 상품, 좋아요, 포인트, 주문, 주문 품목 (가격·재고·잔액·단가·항목 금액·합계·결제액·결제 결과는 컬럼 후보, 좋아요 수는 컬럼 없음)
- 참조 개념 → ID 컬럼만, FK 금지: 사용자 ID(좋아요·포인트·주문), 상품 ID(좋아요·주문 품목)
- AG → FK 허용 범위: 주문–주문 품목만
- 2-4 일관성 → 모두 즉시이므로 "같은 트랜잭션 금지" 쌍 없음

---

## 3. 데이터 (Level 4)

컬럼 타입은 논리 타입으로 적는다. `bigint`(64비트 부호 있는 정수), `int`(32비트), `varchar(n)`, `boolean`, `timestamp`. 모든 테이블은 공통 컬럼 `id`(bigint, 자동 증가 PK), `created_at`, `updated_at`, `deleted_at`(NULL 허용)을 가진다 (DR-01, DR-16). 아래 표에서 이 넷은 각 테이블 첫 줄과 끝 줄에 두고 의미는 쓰이는 곳에만 적는다. 원문에 없는 값(길이·범위·정렬 보조 기준)은 DR-11에 모아 두고 요구사항 1-4에 되돌려 보낸다.

### 3-1. 소유 매핑

| TB-ID | 테이블명 | 소유 BC | 소속 AG | 대응 소유 개념 |
|---|---|---|---|---|
| TB-01 | `user` | BC-01 | AG-01 | 사용자 (관리자는 컬럼) |
| TB-02 | `brand` | BC-02 | AG-02 | 브랜드 |
| TB-03 | `product` | BC-02 | AG-03 | 상품 (가격·재고는 컬럼) |
| TB-04 | `product_like` | BC-02 | AG-04 | 좋아요 (DR-10) |
| TB-05 | `point` | BC-03 | AG-05 | 포인트 (잔액은 컬럼) |
| TB-06 | `orders` | BC-04 | AG-06 | 주문 (합계·결제액·결제 결과는 컬럼, DR-10, DR-13) |
| TB-07 | `order_item` | BC-04 | AG-06 | 주문 품목 (단가는 컬럼, 항목 금액은 계산값) |

좋아요 수·충전·환불·운영자 충전/차감·표현 범위는 테이블이 없다. 좋아요 수는 TB-04의 개수, 나머지는 행위 또는 값 정의다.

### 3-2. 테이블 정의

### TB-01 `user`

- 소유 BC: BC-01 / 소속 AG: AG-01 / 대응 개념: 사용자
- 기본키: `id`
- 고유 제약: 없음

| 컬럼 | 타입 | NULL | 제약 | 의미 | 출처 |
|---|---|---|---|---|---|
| `id` | bigint | N | PK, 자동 증가 | 사용자 식별자 = `X-USER-ID` 값 | 시스템 |
| `is_admin` | boolean | N | | 관리자 권한 여부 (INV-15) | 소유 |
| `created_at` | timestamp | N | | | 시스템 |
| `updated_at` | timestamp | N | | | 시스템 |
| `deleted_at` | timestamp | Y | | 삭제 시각. 삭제되지 않았으면 NULL | 시스템 |

fixture로만 채워진다. 런타임에 이 테이블에 쓰는 FR은 없다. `deleted_at`은 항상 NULL (삭제 FR 없음).

### TB-02 `brand`

- 소유 BC: BC-02 / 소속 AG: AG-02 / 대응 개념: 브랜드
- 기본키: `id`
- 고유 제약: 없음
- 상태: `deleted_at`이 ST-01을 담는다 (NULL = ACTIVE, NOT NULL = DELETED. DR-16)

| 컬럼 | 타입 | NULL | 제약 | 의미 | 출처 |
|---|---|---|---|---|---|
| `id` | bigint | N | PK, 자동 증가 | 브랜드 식별자 | 시스템 |
| `name` | varchar(100) | N | 길이 1~100 (INV-14, DR-11) | 브랜드 이름 | 소유 |
| `created_at` | timestamp | N | | 관리자 목록 최신순 기준 | 시스템 |
| `updated_at` | timestamp | N | | | 시스템 |
| `deleted_at` | timestamp | Y | ST-01 | 브랜드 상태. NULL = ACTIVE, NOT NULL = DELETED(삭제 시각) | 소유 |

### TB-03 `product`

- 소유 BC: BC-02 / 소속 AG: AG-03 / 대응 개념: 상품
- 기본키: `id`
- 고유 제약: 없음
- 상태: `deleted_at`이 ST-02를 담는다 (NULL = ACTIVE, NOT NULL = DELETED. DR-16)

| 컬럼 | 타입 | NULL | 제약 | 의미 | 출처 |
|---|---|---|---|---|---|
| `id` | bigint | N | PK, 자동 증가 | 상품 식별자 | 시스템 |
| `brand_id` | bigint | N | FK 없음 (AG 간, 3-3) | 소속 브랜드. 생성 후 불변 (INV-11) | 소유 |
| `name` | varchar(200) | N | 길이 1~200 (INV-13, DR-11) | 상품 이름 | 소유 |
| `price` | bigint | N | ≥ 0, 표현 범위 안 (INV-13, ASM-04) | 가격. 현재 값이며 관리자가 바꾼다 | 소유 |
| `stock` | int | N | ≥ 0 (INV-03) | 재고 | 소유 |
| `created_at` | timestamp | N | | `latest` 정렬 기준 (ASM-08) | 시스템 |
| `updated_at` | timestamp | N | | | 시스템 |
| `deleted_at` | timestamp | Y | ST-02 | 상품 상태. NULL = ACTIVE, NOT NULL = DELETED(삭제 시각) | 소유 |

### TB-04 `product_like`

- 소유 BC: BC-02 / 소속 AG: AG-04 / 대응 개념: 좋아요
- 기본키: `id`
- 고유 제약: (`user_id`, `product_id`) — INV-04

| 컬럼 | 타입 | NULL | 제약 | 의미 | 출처 |
|---|---|---|---|---|---|
| `id` | bigint | N | PK, 자동 증가 | 좋아요 식별자 | 시스템 |
| `user_id` | bigint | N | FK 없음 | 좋아요를 누른 사용자 | 참조: BC-01 |
| `product_id` | bigint | N | FK 없음 (AG 간, 3-3) | 대상 상품. 상품이 DELETED여도 유지 (ASM-07) | 소유 |
| `created_at` | timestamp | N | | 내 좋아요 목록 최신순 기준 (FR-LIKE-03) | 시스템 |
| `updated_at` | timestamp | N | | | 시스템 |
| `deleted_at` | timestamp | Y | | 삭제 시각. 삭제되지 않았으면 NULL | 시스템 |

### TB-05 `point`

- 소유 BC: BC-03 / 소속 AG: AG-05 / 대응 개념: 포인트
- 기본키: `id`
- 고유 제약: (`user_id`) — 사용자마다 포인트 계정 하나 (DR-12)

| 컬럼 | 타입 | NULL | 제약 | 의미 | 출처 |
|---|---|---|---|---|---|
| `id` | bigint | N | PK, 자동 증가 | 포인트 계정 식별자 | 시스템 |
| `user_id` | bigint | N | FK 없음, 고유 | 잔액의 주인 | 참조: BC-01 |
| `balance` | bigint | N | ≥ 0 (INV-01), 표현 범위 안 (INV-02) | 잔액. 단일 저장값 (DR-07) | 소유 |
| `created_at` | timestamp | N | | | 시스템 |
| `updated_at` | timestamp | N | | | 시스템 |
| `deleted_at` | timestamp | Y | | 삭제 시각. 삭제되지 않았으면 NULL | 시스템 |

### TB-06 `orders`

- 소유 BC: BC-04 / 소속 AG: AG-06 / 대응 개념: 주문
- 기본키: `id`
- 고유 제약: 없음

| 컬럼 | 타입 | NULL | 제약 | 의미 | 출처 |
|---|---|---|---|---|---|
| `id` | bigint | N | PK, 자동 증가 | 주문 식별자 | 시스템 |
| `user_id` | bigint | N | FK 없음 | 주문을 만든 사용자(구매자, DR-09) | 참조: BC-01 |
| `status` | varchar(10) | N | ST-03 | DRAFT / CONFIRMED | 소유 |
| `total_amount` | bigint | N | ≥ 0, 표현 범위 안 (ASM-05) | 합계. 생성 시 1회 계산해 저장, 이후 불변 (DR-13) | 소유 |
| `paid_amount` | bigint | Y | CONFIRMED이면 = `total_amount` (INV-09) | 결제액. DRAFT에는 결제액이 존재하지 않으므로 NULL | 소유 |
| `confirmed_at` | timestamp | Y | | 결제 결과: 차감이 이루어진 시각 (ASM-13). DRAFT에는 없으므로 NULL | 소유 |
| `created_at` | timestamp | N | | 내 주문 목록·관리자 목록 최신순 기준 | 시스템 |
| `updated_at` | timestamp | N | | | 시스템 |
| `deleted_at` | timestamp | Y | | 삭제 시각. 삭제되지 않았으면 NULL | 시스템 |

결제 결과(ASM-13)는 `paid_amount` + `confirmed_at` 두 컬럼으로 표현한다. 별도 테이블을 두지 않는다 (DR-13).

### TB-07 `order_item`

- 소유 BC: BC-04 / 소속 AG: AG-06 / 대응 개념: 주문 품목
- 기본키: `id`
- 고유 제약: (`order_id`, `product_id`) — INV-08

| 컬럼 | 타입 | NULL | 제약 | 의미 | 출처 |
|---|---|---|---|---|---|
| `id` | bigint | N | PK, 자동 증가 | 주문 품목 식별자 | 시스템 |
| `order_id` | bigint | N | FK → TB-06 (같은 AG) | 소속 주문 | 소유 |
| `product_id` | bigint | N | FK 없음 | 대상 상품. 상품이 DELETED여도 유지 (원문, ASM-22) | 참조: BC-02 |
| `quantity` | int | N | > 0 (INV-07) | 수량. 같은 상품은 합산된 값 (ASM-11) | 소유 |
| `unit_price` | bigint | N | ≥ 0 | 단가. 주문 생성 시점의 상품 가격 사본 (ASM-10) | 스냅샷: BC-02 |
| `created_at` | timestamp | N | | | 시스템 |
| `updated_at` | timestamp | N | | | 시스템 |
| `deleted_at` | timestamp | Y | | 삭제 시각. 삭제되지 않았으면 NULL | 시스템 |

항목 금액(= `unit_price` × `quantity`)은 저장하지 않는다 (DR-13). TB-04·TB-05·TB-06·TB-07의 `deleted_at`은 공통 컬럼으로 존재하지만 이를 채우는 FR이 없어 항상 NULL이다 (3-5).

### 3-3. 관계와 참조 무결성

| 자식 TB | 자식 컬럼 | 부모 TB | 부모 컬럼 | FK 여부 | 삭제 시 | 근거 |
|---|---|---|---|---|---|---|
| TB-07 `order_item` | `order_id` | TB-06 `orders` | `id` | 예 | CASCADE | AG-06. 실제로 주문을 삭제하는 FR은 없다 |
| TB-03 `product` | `brand_id` | TB-02 `brand` | `id` | 아니오 | 없음 | AG 간 참조. 검증 FR-ADMIN-PRODUCT-02 (존재·ACTIVE). 브랜드는 삭제되지 않고 상태만 바뀌므로 고아는 생기지 않는다 (INV-10) |
| TB-04 `product_like` | `product_id` | TB-03 `product` | `id` | 아니오 | 없음 | AG 간 참조. 검증 FR-LIKE-01 (존재·ACTIVE). DELETED 상품 참조는 의도된 유지 (ASM-07) |
| TB-04 `product_like` | `user_id` | TB-01 `user` | `id` | 아니오 | 없음 | BC 간 참조. 값이 요청자 ID이므로 공통 사전 조건(요청자 식별)이 검증한다 |
| TB-05 `point` | `user_id` | TB-01 `user` | `id` | 아니오 | 없음 | BC 간 참조. fixture에서 사용자와 함께 준비, 런타임 생성 없음 (DR-12) |
| TB-06 `orders` | `user_id` | TB-01 `user` | `id` | 아니오 | 없음 | BC 간 참조. 값이 요청자 ID이므로 공통 사전 조건이 검증한다 (FR-ORDER-01) |
| TB-07 `order_item` | `product_id` | TB-03 `product` | `id` | 아니오 | 없음 | BC 간 참조. 검증 FR-ORDER-01 (존재·ACTIVE), FR-ORDER-02 재검증. DELETED 상품 참조는 의도된 유지 |

### 3-4. 상태 컬럼

| ST-ID | 요구사항 3-2 용어 | TB.컬럼 | 상태값 목록 | 종료 상태 | 실패로 인한 전이 |
|---|---|---|---|---|---|
| ST-01 | 브랜드 | TB-02.`deleted_at` (NULL / NOT NULL) | ACTIVE, DELETED | DELETED | 없음 |
| ST-02 | 상품 | TB-03.`deleted_at` (NULL / NOT NULL) | ACTIVE, DELETED | DELETED | 없음 |
| ST-03 | 주문 | TB-06.`status` | DRAFT, CONFIRMED | CONFIRMED | 없음 (ASM-12: 모든 실패가 "유지") |

### 3-5. 삭제와 이력

| TB-ID | 삭제 방식 | 이력 보존 | 근거 FR-ID |
|---|---|---|---|
| TB-01 `user` | 삭제 없음 | 아니오 | — |
| TB-02 `brand` | 논리 삭제 (`deleted_at`, ST-01. DR-16) | 아니오 | FR-ADMIN-BRAND-05 |
| TB-03 `product` | 논리 삭제 (`deleted_at`, ST-02. DR-16) | 아니오 | FR-ADMIN-PRODUCT-05 |
| TB-04 `product_like` | 물리 삭제 (`deleted_at` 미사용, DR-17) | 아니오 | FR-LIKE-02 |
| TB-05 `point` | 삭제 없음 | 아니오 (DR-07) | — |
| TB-06 `orders` | 삭제 없음 (DRAFT 삭제·만료는 범위 밖) | 아니오 | — |
| TB-07 `order_item` | 삭제 없음 (부모 CASCADE 정의만) | 아니오 | — |

메모
- 모든 테이블에 `deleted_at`이 공통으로 있으므로, 브랜드·상품의 상태(요구사항 3-2 ACTIVE/DELETED)는 별도 `status` 컬럼 없이 `deleted_at`의 NULL 여부로 표현한다. 둘 다 두면 같은 사실이 두 곳에 산다 (DR-16).
- 삭제 FR이 없는 테이블의 `deleted_at`은 항상 NULL이다. 조회 조건에 `deleted_at IS NULL`을 넣지 않는다(3-7-A). 넣으면 "삭제될 수 있다"는 거짓 신호가 된다.
- "모든 조회에서 삭제 행 제외"는 고객 FR에만 적용된다. 관리자 FR은 삭제 행을 포함하고 상태를 함께 돌려준다 (ASM-15). 3-7-A 조건 컬럼에 그대로 반영했다.

### 3-6. 동시성 제어

없음. 요구사항 FR에 동시성 비기능 문장이 없다. 재고·잔액·INV-10의 동시 요청 창은 OQ-02, OQ-03에 열려 있고 이번 범위에서 다루지 않는다. 버전 컬럼도 두지 않는다.

### 3-7. 조회 패턴과 인덱스

#### 3-7-A. 조회 패턴

공통: 모든 FR은 TB-01 `user`를 `id`(PK)로 한 건 읽어 요청자를 식별한다(관리자 FR은 `is_admin`까지). 아래 표의 "읽는 TB"와 "걸치는 BC 수"에서는 이 공통 조회를 뺐다 (OQ-01과 같은 결).

예상 빈도는 비기능이 없어 상대 추정이다 (DR-14).

| FR-ID | 읽는 TB | 조건 컬럼 | 정렬/페이징 | 예상 빈도 | 걸치는 BC 수 |
|---|---|---|---|---|---|
| FR-BRAND-01 | TB-02 | `id`, `deleted_at` IS NULL | — | 중간 | 1 |
| FR-PRODUCT-01 | TB-03, TB-02, TB-04(개수) | TB-03.`deleted_at` IS NULL; TB-02.`id`=TB-03.`brand_id`; TB-04.`product_id` | `latest`: `created_at` desc, `id` desc / `price_asc`: `price` asc, `id` desc / `likes_desc`: count(TB-04) desc, `id` desc. `page`, `size` (ASM-08) | 높음 | 1 |
| FR-PRODUCT-02 | TB-03, TB-02, TB-04(개수) | TB-03.`id`, `deleted_at` IS NULL; TB-02.`id`; TB-04.`product_id` | — | 높음 | 1 |
| FR-LIKE-01 | TB-03, TB-04 | TB-03.`id`, `deleted_at` IS NULL; TB-04.(`user_id`, `product_id`) | — | 중간 | 1 |
| FR-LIKE-02 | TB-03, TB-04 | TB-03.`id`; TB-04.(`user_id`, `product_id`) | — | 중간 | 1 |
| FR-LIKE-03 | TB-04, TB-03 | TB-04.`user_id`; TB-03.`id`=TB-04.`product_id`, TB-03.`deleted_at` IS NULL | TB-04.`created_at` desc, `id` desc. `page`, `size` (ASM-20) | 중간 | 1 |
| FR-POINT-01 | TB-05 | `user_id` | — | 중간 | 1 |
| FR-POINT-02 | TB-05 | `user_id` | — | 중간 | 1 |
| FR-POINT-03 | TB-05 | `user_id` | — | 낮음 | 1 |
| FR-ORDER-01 | TB-03 | `id` IN (품목 상품), `deleted_at` IS NULL | — | 중간 | 1 (BC-02만. 진입 BC-04의 Facade가 BC-02 Service를 조회, 5-6) |
| FR-ORDER-02 | TB-06, TB-07, TB-03, TB-05 | TB-06.`id`; TB-07.`order_id`; TB-03.`id` IN, `deleted_at` IS NULL; TB-05.`user_id` | — | 중간 | 3 (BC-04, BC-02, BC-03. Facade 조합, 5-6) |
| FR-ORDER-03 | TB-06, TB-07 | TB-06.`user_id`; TB-07.`order_id` IN | TB-06.`created_at` desc, `id` desc. `page`, `size` (ASM-20) | 중간 | 1 |
| FR-ORDER-04 | TB-06, TB-07 | TB-06.`id`; TB-07.`order_id` | — | 중간 | 1 |
| FR-ADMIN-BRAND-01 | TB-02 | — | `created_at` desc, `id` desc. `page`, `size` (ASM-20) | 낮음 | 1 |
| FR-ADMIN-BRAND-02 | 없음 | — | — | 낮음 | 0 |
| FR-ADMIN-BRAND-03 | TB-02 | `id` | — | 낮음 | 1 |
| FR-ADMIN-BRAND-04 | TB-02 | `id`, `deleted_at` IS NULL | — | 낮음 | 1 |
| FR-ADMIN-BRAND-05 | TB-02, TB-03 | TB-02.`id`, `deleted_at` IS NULL; TB-03.`brand_id`, `deleted_at` IS NULL (존재 여부) | — | 낮음 | 1 |
| FR-ADMIN-PRODUCT-01 | TB-03, TB-02, TB-04(개수) | TB-02.`id`=TB-03.`brand_id`; TB-04.`product_id` | TB-03.`created_at` desc, `id` desc. `page`, `size` (ASM-20) | 낮음 | 1 |
| FR-ADMIN-PRODUCT-02 | TB-02 | `id`, `deleted_at` IS NULL | — | 낮음 | 1 |
| FR-ADMIN-PRODUCT-03 | TB-03, TB-02, TB-04(개수) | TB-03.`id`; TB-02.`id`; TB-04.`product_id` | — | 낮음 | 1 |
| FR-ADMIN-PRODUCT-04 | TB-03 | `id`, `deleted_at` IS NULL | — | 낮음 | 1 |
| FR-ADMIN-PRODUCT-05 | TB-03 | `id`, `deleted_at` IS NULL | — | 낮음 | 1 |
| FR-ADMIN-PRODUCT-06 | TB-03 | `id`, `deleted_at` IS NULL | — | 낮음 | 1 |
| FR-ADMIN-ORDER-01 | TB-06, TB-07 | 1단계: distinct TB-06.`user_id`를 페이지로 자름 / 2단계: TB-06.`user_id` IN, TB-07.`order_id` IN | 묶음 순서: `user_id` asc (DR-11). 묶음 안: TB-06.`created_at` desc, `id` desc. `page`, `size`는 묶음 단위 (ASM-19, ASM-20) | 낮음 | 1 |
| FR-ADMIN-ORDER-02 | TB-06, TB-07 | TB-06.`id`; TB-07.`order_id` | — | 낮음 | 1 |
| FR-ADMIN-POINT-01 | TB-01(대상 사용자), TB-05 | TB-01.`id`=바디 userId; TB-05.`user_id` | — | 낮음 | 2 (BC-01, BC-03. Facade 조합, 5-6) |
| FR-ADMIN-POINT-02 | TB-01(대상 사용자), TB-05 | TB-01.`id`=바디 userId; TB-05.`user_id` | — | 낮음 | 2 (BC-01, BC-03. Facade 조합, 5-6) |

메모
- FR-PRODUCT-01의 `likes_desc`는 TB-03과 TB-04가 같은 BC(BC-02)이므로 한 조회(조인 + 집계)로 처리한다. DR-02가 이걸 위해 있었다.
- 걸치는 BC 수 2 이상인 FR-ORDER-02, FR-ADMIN-POINT-01/02는 여러 BC 기준의 정렬·페이징이 없다. 각 BC를 ID로 한 건씩 조회하므로 Facade 조합으로 충분하다. OQ에 올릴 것 없음.
- FR-ADMIN-ORDER-01의 2단계 조회는 한 BC 안이다. 묶음 순서는 원문에 없어 DR-11에서 정했다.

#### 3-7-B. 인덱스

| IX-ID | TB-ID | 컬럼 (순서대로) | 유형 | 근거 FR-ID |
|---|---|---|---|---|
| IX-01 | TB-04 | `user_id`, `product_id` | 고유 | INV-04 고유 제약. FR-LIKE-01/02 (쌍 조회), FR-LIKE-03 (`user_id` 접두) (DR-15) |
| IX-02 | TB-04 | `product_id` | 일반 | FR-PRODUCT-01/02 좋아요 수 집계 (DR-15) |
| IX-03 | TB-03 | `deleted_at`, `created_at`, `id` | 복합 | FR-PRODUCT-01 `latest` (DR-15) |
| IX-04 | TB-03 | `deleted_at`, `price`, `id` | 복합 | FR-PRODUCT-01 `price_asc` (DR-15) |
| IX-05 | TB-05 | `user_id` | 고유 | 사용자당 하나 (DR-12). FR-POINT-01/02/03, FR-ORDER-02, FR-ADMIN-POINT-01/02 |
| IX-06 | TB-06 | `user_id`, `created_at`, `id` | 복합 | FR-ORDER-03 (내 주문 최신순), FR-ADMIN-ORDER-01 (구매자 묶음) (DR-15) |
| IX-07 | TB-07 | `order_id`, `product_id` | 고유 | INV-08 고유 제약. FK `order_id` 조회(접두)도 겸한다 |

만들지 않은 것
- TB-03.`brand_id`: 읽는 FR이 FR-ADMIN-BRAND-05뿐이고 빈도 낮음. 가이드 규칙대로 두지 않는다. 브랜드 삭제가 느려지면 재검토.
- TB-02 `created_at`: 관리자 목록만 쓰고 빈도 낮음.
- `likes_desc` 정렬용: 집계 결과 정렬이라 인덱스로 못 푼다. IX-02가 집계 자체를 돕는다.

### 3-8. 트랜잭션 경계 확인

2-4와 전부 일치.

- FR-ORDER-02가 TB-03(AG-03)·TB-05(AG-05)·TB-06(AG-06)을 한 트랜잭션에서 쓴다 → 2-4에 "즉시 (다중 AG), DR-08"이 있다.
- FR-ADMIN-BRAND-05는 TB-02만 쓰고 TB-03은 읽는다. FR-ADMIN-PRODUCT-02는 TB-03만 쓰고 TB-02는 읽는다 → 2-4와 같다 (DR-04).
- 나머지 명령 FR은 전부 테이블 하나(FR-ORDER-01은 같은 AG의 TB-06·TB-07)를 쓴다.
- 최종 일관성 FR 없음. 3-4 실패로 인한 전이 없음.

### 3-9. 완료 조건

- [x] 모든 TB가 정확히 하나의 소유 BC를 가진다
- [x] 모든 TB의 대응 소유 개념이 2-1에 있다
- [x] 모든 TB가 정확히 하나의 소속 AG를 가진다
- [x] 출처 `소유`인 컬럼이 두 테이블에 같은 사실로 존재하지 않는다 (단가는 `스냅샷`, 결제액과 합계는 같은 테이블 안의 서로 다른 개념 — DR-13)
- [x] 모든 FK가 같은 AG 안에 있다 (TB-07→TB-06 하나뿐)
- [x] FK 없는 참조마다 검증 FR 또는 준비 방식이 명시되어 있다
- [x] 요구사항 3-2의 세 용어가 각각 ST- 하나를 가진다 (브랜드·상품은 `deleted_at`, 주문은 `status`)
- [x] 동시성 비기능이 없으므로 3-6은 "없음"이고 버전 컬럼도 없다
- [x] 28개 FR이 전부 3-7-A에 있다
- [x] 걸치는 BC 수 2 이상인 조회가 SQL 조인으로 처리되지 않는다 (Facade 조합)
- [x] 모든 IX-에 근거 FR이 있다
- [x] 3-8이 "전부 일치"다
- [x] 금지어 없음 (클래스·패키지·URL·ORM 설정 없음. DB 제품명 없음)

4장으로 인계되는 것
- 3-1 소유 매핑 → 각 EP가 노출하는 데이터의 소유 BC
- 3-4 ST-01/02/03 → 상태 변경 EP의 허용 전이와 ER
- 3-6 없음 → 동시성 ER 없음
- 3-7-A 정렬/페이징 → 목록 EP의 정렬 옵션(상품 목록만)과 `page`, `size`
- 요청·응답 필드는 컬럼명이 아니라 개념 언어로 다시 쓴다

## 4. 계약 (Level 5)

경로는 원문(CON-01)과 ASM-23이 정한 것을 그대로 쓴다. 필드명은 camelCase. 모든 EP는 헤더 `X-USER-ID`(Long, 필수, 출처 개념: 사용자)를 받으며 4-3 블록에서 반복하지 않는다. 공통 응답 형태는 4-3-0에 한 번 정의하고 EP 블록에서 이름으로 참조한다 (DR-21).

### 4-1. 공통 규약

| 항목 | 1-3 기본값 | 이 프로젝트 | 근거 |
|---|---|---|---|
| 버전 표기 | `/api/v1` | 고객 `/api/v1`, 관리자 `/api-admin/v1` | CON-02 |
| 에러 코드 체계 | 범용 `ErrorType` | **도메인별 코드.** 요구사항 실패 케이스 이름 그대로 `errorCode`에 싣는다 | FR-POINT-01 (`BALANCE_LIMIT_EXCEEDED` 구분), FR-POINT-03 (`INVALID_AMOUNT` vs `INSUFFICIENT_POINT`), FR-ORDER-02 (`INSUFFICIENT_STOCK` vs `INSUFFICIENT_POINT`)에 "클라이언트가 구분해야 함" 명시 |
| HTTP 상태 고정 매핑 | 검증 400 / 권한 403 / 참조 없음 404 / INV·ST 409 / 서버 500 | 동일. 요청자 식별 실패(`USER_NOT_FOUND`)도 참조 없음 404로 둔다 | DR-18 |
| 검증 에러 형식 | 400 + `message` (확정 필요) | 확정: 400 + `message`에 실패 필드와 이유 한 문장. `details` 미사용 | — |
| 페이징 | 오프셋 (확정 필요) | 확정: 쿼리 `page`(0부터, 기본 0), `size`(1~100, 기본 20). 페이지 정보는 `meta`가 아니라 `data` 안 (`items`, `page`, `size`, `totalCount`) | DR-19 |
| 인증 전달 | 헤더 | `X-USER-ID`. 시스템이 넣는 값이라 형식 오류를 구분하지 않는다 | CON-03, DR-01 |
| 식별자 | 내부 PK 노출 | 동일. Long | DR-01 |
| 나머지 (응답 봉투, 시간 ISO 8601 UTC, 금액 정수, 멱등 키 미사용, 계약 변경) | | 1-3과 동일 | — |

### 4-2. 엔드포인트 목록

| EP-ID | 메서드 | 경로 | 종류 | 근거 FR-ID | 소유 BC |
|---|---|---|---|---|---|
| EP-01 | GET | `/api/v1/brands/{brandId}` | 조회 | FR-BRAND-01 | BC-02 |
| EP-02 | GET | `/api/v1/products` | 조회 | FR-PRODUCT-01 | BC-02 |
| EP-03 | GET | `/api/v1/products/{productId}` | 조회 | FR-PRODUCT-02 | BC-02 |
| EP-04 | POST | `/api/v1/products/{productId}/likes` | 명령 | FR-LIKE-01 | BC-02 |
| EP-05 | DELETE | `/api/v1/products/{productId}/likes` | 명령 | FR-LIKE-02 | BC-02 |
| EP-06 | GET | `/api/v1/users/{userId}/likes` | 조회 | FR-LIKE-03 | BC-02 (경로 리소스 `users`는 참조 개념, DR-20) |
| EP-07 | POST | `/api/v1/points/charge` | 명령 | FR-POINT-01 | BC-03 |
| EP-08 | GET | `/api/v1/points` | 조회 | FR-POINT-02 | BC-03 |
| EP-09 | POST | `/api/v1/points/refund` | 명령 | FR-POINT-03 | BC-03 |
| EP-10 | POST | `/api/v1/orders` | 명령 | FR-ORDER-01 | BC-04 |
| EP-11 | POST | `/api/v1/orders/{orderId}/confirm` | 명령 | FR-ORDER-02 | BC-04 |
| EP-12 | GET | `/api/v1/orders` | 조회 | FR-ORDER-03 | BC-04 |
| EP-13 | GET | `/api/v1/orders/{orderId}` | 조회 | FR-ORDER-04 | BC-04 |
| EP-14 | GET | `/api-admin/v1/brands` | 조회 | FR-ADMIN-BRAND-01 | BC-02 |
| EP-15 | POST | `/api-admin/v1/brands` | 명령 | FR-ADMIN-BRAND-02 | BC-02 |
| EP-16 | GET | `/api-admin/v1/brands/{brandId}` | 조회 | FR-ADMIN-BRAND-03 | BC-02 |
| EP-17 | PUT | `/api-admin/v1/brands/{brandId}` | 명령 | FR-ADMIN-BRAND-04 | BC-02 |
| EP-18 | DELETE | `/api-admin/v1/brands/{brandId}` | 명령 | FR-ADMIN-BRAND-05 | BC-02 |
| EP-19 | GET | `/api-admin/v1/products` | 조회 | FR-ADMIN-PRODUCT-01 | BC-02 |
| EP-20 | POST | `/api-admin/v1/products` | 명령 | FR-ADMIN-PRODUCT-02 | BC-02 |
| EP-21 | GET | `/api-admin/v1/products/{productId}` | 조회 | FR-ADMIN-PRODUCT-03 | BC-02 |
| EP-22 | PUT | `/api-admin/v1/products/{productId}` | 명령 | FR-ADMIN-PRODUCT-04 | BC-02 |
| EP-23 | DELETE | `/api-admin/v1/products/{productId}` | 명령 | FR-ADMIN-PRODUCT-05 | BC-02 |
| EP-24 | PUT | `/api-admin/v1/products/{productId}/stock` | 명령 | FR-ADMIN-PRODUCT-06 | BC-02 |
| EP-25 | GET | `/api-admin/v1/orders` | 조회 | FR-ADMIN-ORDER-01 | BC-04 |
| EP-26 | GET | `/api-admin/v1/orders/{orderId}` | 조회 | FR-ADMIN-ORDER-02 | BC-04 |
| EP-27 | POST | `/api-admin/v1/points/charge` | 명령 | FR-ADMIN-POINT-01 | BC-03 (ASM-23 경로 변경, DR-20) |
| EP-28 | POST | `/api-admin/v1/points/deduct` | 명령 | FR-ADMIN-POINT-02 | BC-03 (ASM-23 경로 변경, DR-20) |

FR 28개 ↔ EP 28개, 1:1.

### 4-3. 엔드포인트 정의

#### 4-3-0. 공통 형태

**페이지 `Page<T>`** (모든 목록 조회)

| 필드 | 타입 | NULL | 의미 | 출처 |
|---|---|---|---|---|
| `items` | T[] | N | 이 페이지의 항목 | (T의 출처) |
| `page` | int | N | 요청한 페이지 번호 | 계산 |
| `size` | int | N | 요청한 크기 | 계산 |
| `totalCount` | long | N | 전체 항목 수 (EP-25는 구매자 묶음 수) | 계산 |

**`BrandSummary`** (고객) — 근거 FR-BRAND-01, FR-PRODUCT-01/02 "브랜드 정보"

| 필드 | 타입 | NULL | 의미 | 출처 |
|---|---|---|---|---|
| `id` | long | N | 브랜드 식별자 | TB-02.`id` |
| `name` | string | N | 브랜드 이름 | TB-02.`name` |

**`BrandAdmin`** (관리자) — 근거 FR-ADMIN-BRAND-01/02/03/04 "삭제 여부 표시"

| 필드 | 타입 | NULL | 의미 | 출처 |
|---|---|---|---|---|
| `id` | long | N | 브랜드 식별자 | TB-02.`id` |
| `name` | string | N | 브랜드 이름 | TB-02.`name` |
| `deleted` | boolean | N | 삭제 여부 (ST-01) | 계산 (TB-02.`deleted_at` IS NOT NULL) |

**`ProductSummary`** (고객 목록·상세 공통) — 근거 FR-PRODUCT-01/02 "상품 정보 + 브랜드 정보 + 좋아요 수". 재고는 요구사항 출력에 없어 넣지 않는다 (OQ-04)

| 필드 | 타입 | NULL | 의미 | 출처 |
|---|---|---|---|---|
| `id` | long | N | 상품 식별자 | TB-03.`id` |
| `name` | string | N | 상품 이름 | TB-03.`name` |
| `price` | long | N | 가격 | TB-03.`price` |
| `brand` | BrandSummary | N | 소속 브랜드 | TB-02 (같은 BC) |
| `likeCount` | long | N | 좋아요 수 (INV-05) | 계산 (TB-04 개수) |

**`ProductAdmin`** (관리자) — 근거 FR-ADMIN-PRODUCT-01/02/03/04 "브랜드 정보·재고·삭제 여부·좋아요 수"

| 필드 | 타입 | NULL | 의미 | 출처 |
|---|---|---|---|---|
| `id` | long | N | 상품 식별자 | TB-03.`id` |
| `name` | string | N | 상품 이름 | TB-03.`name` |
| `price` | long | N | 가격 | TB-03.`price` |
| `stock` | int | N | 재고 | TB-03.`stock` |
| `brand` | BrandSummary | N | 소속 브랜드 | TB-02 (같은 BC) |
| `likeCount` | long | N | 좋아요 수 | 계산 (TB-04 개수) |
| `deleted` | boolean | N | 삭제 여부 (ST-02) | 계산 (TB-03.`deleted_at` IS NOT NULL) |

**`LikeItem`** — 근거 FR-LIKE-03 "각 항목에 상품 정보"

| 필드 | 타입 | NULL | 의미 | 출처 |
|---|---|---|---|---|
| `likedAt` | datetime | N | 좋아요 등록 시각 (정렬 기준) | TB-04.`created_at` |
| `product` | object | N | 상품 정보 | — |
| `product.id` | long | N | 상품 식별자 | TB-03.`id` |
| `product.name` | string | N | 상품 이름 | TB-03.`name` |
| `product.price` | long | N | 가격 | TB-03.`price` |
| `product.brand` | BrandSummary | N | 소속 브랜드 | TB-02 |

**`PointBalance`** — 근거 FR-POINT-01/02/03, FR-ADMIN-POINT-01/02 "잔액"

| 필드 | 타입 | NULL | 의미 | 출처 |
|---|---|---|---|---|
| `userId` | long | N | 잔액의 주인 | TB-05.`user_id` |
| `balance` | long | N | 잔액 | TB-05.`balance` |

**`OrderItemResponse`**

| 필드 | 타입 | NULL | 의미 | 출처 |
|---|---|---|---|---|
| `productId` | long | N | 상품 식별자 (참조 ID만) | TB-07.`product_id` |
| `quantity` | int | N | 수량 | TB-07.`quantity` |
| `unitPrice` | long | N | 단가 (생성 시점 사본) | TB-07.`unit_price` |
| `lineAmount` | long | N | 항목 금액 = 단가 × 수량 (INV-06) | 계산 |

**`OrderResponse`** (고객) — 근거 FR-ORDER-01/02/03/04 "품목·수량·금액·상태·결제액"

| 필드 | 타입 | NULL | 의미 | 출처 |
|---|---|---|---|---|
| `id` | long | N | 주문 식별자 | TB-06.`id` |
| `status` | string | N | DRAFT / CONFIRMED (ST-03) | TB-06.`status` |
| `totalAmount` | long | N | 합계 | TB-06.`total_amount` |
| `paidAmount` | long | Y | 결제액. DRAFT면 null | TB-06.`paid_amount` |
| `confirmedAt` | datetime | Y | 결제 결과(확정 시각). DRAFT면 null | TB-06.`confirmed_at` |
| `items` | OrderItemResponse[] | N | 주문 품목 | TB-07 |
| `createdAt` | datetime | N | 생성 시각 (목록 정렬 기준) | TB-06.`created_at` |

**`AdminOrderResponse`** — `OrderResponse` + 아래. 근거 FR-ADMIN-ORDER-01/02 "구매자·결제 결과"

| 필드 | 타입 | NULL | 의미 | 출처 |
|---|---|---|---|---|
| `userId` | long | N | 구매자 (참조 ID만, DR-09) | TB-06.`user_id` |

**`AdminOrderGroup`** — 근거 FR-ADMIN-ORDER-01 "구매자별 묶음" (ASM-19)

| 필드 | 타입 | NULL | 의미 | 출처 |
|---|---|---|---|---|
| `userId` | long | N | 구매자 | TB-06.`user_id` |
| `orders` | AdminOrderResponse[] | N | 이 구매자의 주문, 최신순 | TB-06, TB-07 |

**`StockResponse`** — 근거 FR-ADMIN-PRODUCT-06 "변경된 재고"

| 필드 | 타입 | NULL | 의미 | 출처 |
|---|---|---|---|---|
| `productId` | long | N | 상품 식별자 | TB-03.`id` |
| `stock` | int | N | 재고 | TB-03.`stock` |

공통 요청 제약 (경로 ID): 모든 `{…Id}`는 long. 형식이 아닌 값은 해당 `*_NOT_FOUND`로 처리한다 (DR-01).

---

### EP-01 GET `/api/v1/brands/{brandId}`
- 근거 FR: FR-BRAND-01 / 소유 BC: BC-02 / 종류: 조회 / 권한: 고객 / 멱등성: 예
- 요청: 경로 `brandId` (long, 필수, 출처: 브랜드)
- 응답 200: `BrandSummary`
- 상태 전이: 해당 없음
- 에러: ER-01 (요청자 없음) / ER-03 (브랜드 없음 또는 삭제됨)

### EP-02 GET `/api/v1/products`
- 근거 FR: FR-PRODUCT-01 / 소유 BC: BC-02 / 종류: 조회 / 권한: 고객 / 멱등성: 예
- 요청

| 위치 | 필드 | 타입 | 필수 | 제약 | 의미 | 출처 개념 |
|---|---|---|---|---|---|---|
| 쿼리 | `sort` | string | 아니오 | `latest` \| `price_asc` \| `likes_desc`. 정확히 하나. 기본 `latest` (ASM-08) | 정렬 | 상품, 가격, 좋아요 수 |
| 쿼리 | `page` | int | 아니오 | ≥ 0, 기본 0 | 페이지 번호 | — |
| 쿼리 | `size` | int | 아니오 | 1~100, 기본 20 (DR-19) | 페이지 크기 | — |

- 응답 200: `Page<ProductSummary>` (삭제되지 않은 상품만)
- 상태 전이: 해당 없음
- 에러: ER-01 / ER-07 (sort가 허용 목록 밖 또는 둘 이상) / ER-08 (page·size 범위·타입 오류)

### EP-03 GET `/api/v1/products/{productId}`
- 근거 FR: FR-PRODUCT-02 / 소유 BC: BC-02 / 종류: 조회 / 권한: 고객 / 멱등성: 예
- 요청: 경로 `productId` (long, 필수, 출처: 상품)
- 응답 200: `ProductSummary`
- 상태 전이: 해당 없음
- 에러: ER-01 / ER-04 (상품 없음 또는 삭제됨)

### EP-04 POST `/api/v1/products/{productId}/likes`
- 근거 FR: FR-LIKE-01 / 소유 BC: BC-02 / 종류: 명령 / 권한: 고객 / 멱등성: 예 (ASM-06)
- 요청: 경로 `productId` (long, 필수, 출처: 상품). 바디 없음
- 응답 200: `data` null (FR 출력 없음, DR-21)
- 상태 전이: 해당 없음 (좋아요는 상태 없음)
- 에러: ER-01 / ER-04 (상품 없음 또는 삭제됨, ASM-07)

### EP-05 DELETE `/api/v1/products/{productId}/likes`
- 근거 FR: FR-LIKE-02 / 소유 BC: BC-02 / 종류: 명령 / 권한: 고객 / 멱등성: 예 (ASM-06)
- 요청: 경로 `productId` (long, 필수, 출처: 상품)
- 응답 200: `data` null
- 상태 전이: 해당 없음
- 에러: ER-01 / ER-04 (상품 없음. 삭제된 상품은 허용)

### EP-06 GET `/api/v1/users/{userId}/likes`
- 근거 FR: FR-LIKE-03 / 소유 BC: BC-02 / 종류: 조회 / 권한: 고객 / 멱등성: 예
- 요청

| 위치 | 필드 | 타입 | 필수 | 제약 | 의미 | 출처 개념 |
|---|---|---|---|---|---|---|
| 경로 | `userId` | long | 예 | 요청자와 같아야 함 (ASM-09) | 대상 사용자 | 사용자 (참조) |
| 쿼리 | `page`, `size` | int | 아니오 | EP-02와 같음 | | — |

- 응답 200: `Page<LikeItem>` (상품이 삭제되지 않은 것만, 등록 최신순)
- 상태 전이: 해당 없음
- 에러: ER-01 / ER-06 (userId ≠ 요청자) / ER-08

### EP-07 POST `/api/v1/points/charge`
- 근거 FR: FR-POINT-01 / 소유 BC: BC-03 / 종류: 명령 / 권한: 고객 / 멱등성: 아니오
- 요청

| 위치 | 필드 | 타입 | 필수 | 제약 | 의미 | 출처 개념 |
|---|---|---|---|---|---|---|
| 바디 | `amount` | long | 예 | 정수, > 0, 표현 범위 안 (DR-11) | 충전액 | 충전 |

- 응답 200: `PointBalance` (충전 후 잔액)
- 상태 전이: 해당 없음
- 에러: ER-01 / ER-09 (amount 누락·정수 아님·≤ 0·범위 초과) / ER-10 (잔액 + amount 범위 초과)

### EP-08 GET `/api/v1/points`
- 근거 FR: FR-POINT-02 / 소유 BC: BC-03 / 종류: 조회 / 권한: 고객 / 멱등성: 예
- 요청: 헤더만
- 응답 200: `PointBalance`
- 상태 전이: 해당 없음
- 에러: ER-01

### EP-09 POST `/api/v1/points/refund`
- 근거 FR: FR-POINT-03 / 소유 BC: BC-03 / 종류: 명령 / 권한: 고객 / 멱등성: 아니오
- 요청: 바디 `amount` (long, 필수, 정수 > 0, 표현 범위 안, 출처: 환불)
- 응답 200: `PointBalance` (환불 후 잔액)
- 상태 전이: 해당 없음
- 에러: ER-01 / ER-09 / ER-11 (잔액 < amount)

### EP-10 POST `/api/v1/orders`
- 근거 FR: FR-ORDER-01 / 소유 BC: BC-04 / 종류: 명령 / 권한: 고객 / 멱등성: 아니오
- 요청

| 위치 | 필드 | 타입 | 필수 | 제약 | 의미 | 출처 개념 |
|---|---|---|---|---|---|---|
| 바디 | `items` | object[] | 예 | 1개 이상 | 주문 품목 | 주문 품목 |
| 바디 | `items[].productId` | long | 예 | | 상품 (ID만) | 상품 (참조) |
| 바디 | `items[].quantity` | int | 예 | 정수 > 0 | 수량. 같은 productId는 합산 (ASM-11) | 주문 품목 |

- 응답 201: `OrderResponse` (status DRAFT, paidAmount·confirmedAt null)
- 상태 전이: ST-03 — (없음) → DRAFT
- 에러: ER-01 / ER-12 (items 없음·빈 배열) / ER-04 (어느 productId의 상품이 없거나 삭제됨) / ER-13 (어느 quantity가 누락·정수 아님·≤ 0) / ER-14 (합계가 표현 범위 초과)

### EP-11 POST `/api/v1/orders/{orderId}/confirm`
- 근거 FR: FR-ORDER-02 / 소유 BC: BC-04 / 종류: 명령 / 권한: 고객 / 멱등성: 아니오 (두 번째 호출은 ER-15)
- 요청: 경로 `orderId` (long, 필수, 출처: 주문). 바디 없음
- 응답 200: `OrderResponse` (status CONFIRMED, paidAmount = totalAmount, confirmedAt 채워짐)
- 상태 전이: ST-03 — DRAFT → CONFIRMED
- 에러 (검사 순서대로): ER-01 / ER-05 (주문 없음) / ER-06 (요청자의 주문 아님) / ER-15 (DRAFT 아님) / ER-04 (어느 품목의 상품이 삭제됨) / ER-16 (어느 품목 재고 < 수량. `message`에 부족한 productId) / ER-11 (잔액 < 합계). 실패 시 재고·잔액·상태 전부 변화 없음 (ASM-14)

### EP-12 GET `/api/v1/orders`
- 근거 FR: FR-ORDER-03 / 소유 BC: BC-04 / 종류: 조회 / 권한: 고객 / 멱등성: 예
- 요청: 쿼리 `page`, `size` (EP-02와 같음)
- 응답 200: `Page<OrderResponse>` (요청자의 주문 전부, 최신순)
- 상태 전이: 해당 없음
- 에러: ER-01 / ER-08

### EP-13 GET `/api/v1/orders/{orderId}`
- 근거 FR: FR-ORDER-04 / 소유 BC: BC-04 / 종류: 조회 / 권한: 고객 / 멱등성: 예
- 요청: 경로 `orderId` (long, 필수, 출처: 주문)
- 응답 200: `OrderResponse`
- 상태 전이: 해당 없음
- 에러: ER-01 / ER-05 / ER-06

### EP-14 GET `/api-admin/v1/brands`
- 근거 FR: FR-ADMIN-BRAND-01 / 소유 BC: BC-02 / 종류: 조회 / 권한: 관리자 / 멱등성: 예
- 요청: 쿼리 `page`, `size`
- 응답 200: `Page<BrandAdmin>` (삭제 포함, 최신순)
- 상태 전이: 해당 없음
- 에러: ER-01 / ER-02 / ER-08

### EP-15 POST `/api-admin/v1/brands`
- 근거 FR: FR-ADMIN-BRAND-02 / 소유 BC: BC-02 / 종류: 명령 / 권한: 관리자 / 멱등성: 아니오
- 요청: 바디 `name` (string, 필수, 길이 1~100, 출처: 브랜드)
- 응답 201: `BrandAdmin` (deleted false)
- 상태 전이: ST-01 — (없음) → ACTIVE
- 에러: ER-01 / ER-02 / ER-17 (name 누락·빈 값·길이 밖)

### EP-16 GET `/api-admin/v1/brands/{brandId}`
- 근거 FR: FR-ADMIN-BRAND-03 / 소유 BC: BC-02 / 종류: 조회 / 권한: 관리자 / 멱등성: 예
- 요청: 경로 `brandId`
- 응답 200: `BrandAdmin` (삭제 여부 무관)
- 상태 전이: 해당 없음
- 에러: ER-01 / ER-02 / ER-03 (존재하지 않음만)

### EP-17 PUT `/api-admin/v1/brands/{brandId}`
- 근거 FR: FR-ADMIN-BRAND-04 / 소유 BC: BC-02 / 종류: 명령 / 권한: 관리자 / 멱등성: 예
- 요청: 경로 `brandId`. 바디 `name` (string, 필수, 길이 1~100)
- 응답 200: `BrandAdmin`
- 상태 전이: 해당 없음 (ACTIVE 유지)
- 에러: ER-01 / ER-02 / ER-03 (없음 또는 삭제됨) / ER-17

### EP-18 DELETE `/api-admin/v1/brands/{brandId}`
- 근거 FR: FR-ADMIN-BRAND-05 / 소유 BC: BC-02 / 종류: 명령 / 권한: 관리자 / 멱등성: 아니오 (두 번째 호출은 ER-03)
- 요청: 경로 `brandId`
- 응답 200: `data` null
- 상태 전이: ST-01 — ACTIVE → DELETED
- 에러: ER-01 / ER-02 / ER-03 (없음 또는 이미 삭제됨, ASM-18) / ER-18 (삭제되지 않은 상품이 연결됨)

### EP-19 GET `/api-admin/v1/products`
- 근거 FR: FR-ADMIN-PRODUCT-01 / 소유 BC: BC-02 / 종류: 조회 / 권한: 관리자 / 멱등성: 예
- 요청: 쿼리 `page`, `size`
- 응답 200: `Page<ProductAdmin>` (삭제 포함, 최신순)
- 상태 전이: 해당 없음
- 에러: ER-01 / ER-02 / ER-08

### EP-20 POST `/api-admin/v1/products`
- 근거 FR: FR-ADMIN-PRODUCT-02 / 소유 BC: BC-02 / 종류: 명령 / 권한: 관리자 / 멱등성: 아니오
- 요청

| 위치 | 필드 | 타입 | 필수 | 제약 | 의미 | 출처 개념 |
|---|---|---|---|---|---|---|
| 바디 | `brandId` | long | 예 | 존재하고 삭제되지 않은 브랜드 | 소속 브랜드 (ID만) | 브랜드 |
| 바디 | `name` | string | 예 | 길이 1~200 | 상품 이름 | 상품 |
| 바디 | `price` | long | 예 | 정수 ≥ 0, 표현 범위 안 (ASM-04) | 가격 | 가격 |
| 바디 | `stock` | int | 예 | 정수 ≥ 0 (ASM-17) | 재고 | 재고 |

- 응답 201: `ProductAdmin`
- 상태 전이: ST-02 — (없음) → ACTIVE
- 에러: ER-01 / ER-02 / ER-03 (brandId 브랜드 없음 또는 삭제됨) / ER-19 / ER-20 / ER-21

### EP-21 GET `/api-admin/v1/products/{productId}`
- 근거 FR: FR-ADMIN-PRODUCT-03 / 소유 BC: BC-02 / 종류: 조회 / 권한: 관리자 / 멱등성: 예
- 요청: 경로 `productId`
- 응답 200: `ProductAdmin` (삭제 여부 무관)
- 상태 전이: 해당 없음
- 에러: ER-01 / ER-02 / ER-04 (존재하지 않음만)

### EP-22 PUT `/api-admin/v1/products/{productId}`
- 근거 FR: FR-ADMIN-PRODUCT-04 / 소유 BC: BC-02 / 종류: 명령 / 권한: 관리자 / 멱등성: 예
- 요청: 경로 `productId`. 바디 `name` (string, 필수, 1~200), `price` (long, 필수, ≥ 0). `brandId`·`stock`은 받지 않는다 (ASM-16, FR-ADMIN-PRODUCT-06 전용)
- 응답 200: `ProductAdmin`
- 상태 전이: 해당 없음
- 에러: ER-01 / ER-02 / ER-04 (없음 또는 삭제됨) / ER-19 / ER-20

### EP-23 DELETE `/api-admin/v1/products/{productId}`
- 근거 FR: FR-ADMIN-PRODUCT-05 / 소유 BC: BC-02 / 종류: 명령 / 권한: 관리자 / 멱등성: 아니오 (두 번째 호출은 ER-04)
- 요청: 경로 `productId`
- 응답 200: `data` null
- 상태 전이: ST-02 — ACTIVE → DELETED
- 에러: ER-01 / ER-02 / ER-04 (없음 또는 이미 삭제됨, ASM-18)

### EP-24 PUT `/api-admin/v1/products/{productId}/stock`
- 근거 FR: FR-ADMIN-PRODUCT-06 / 소유 BC: BC-02 / 종류: 명령 / 권한: 관리자 / 멱등성: 예 (최종 수량 설정)
- 요청: 경로 `productId`. 바디 `stock` (int, 필수, 정수 ≥ 0, 출처: 재고)
- 응답 200: `StockResponse`
- 상태 전이: 해당 없음
- 에러: ER-01 / ER-02 / ER-04 (없음 또는 삭제됨) / ER-21

### EP-25 GET `/api-admin/v1/orders`
- 근거 FR: FR-ADMIN-ORDER-01 / 소유 BC: BC-04 / 종류: 조회 / 권한: 관리자 / 멱등성: 예
- 요청: 쿼리 `page`, `size` (묶음 단위, ASM-20)
- 응답 200: `Page<AdminOrderGroup>` (구매자 ID 오름차순, 묶음 안 주문 최신순. DR-11)
- 상태 전이: 해당 없음
- 에러: ER-01 / ER-02 / ER-08

### EP-26 GET `/api-admin/v1/orders/{orderId}`
- 근거 FR: FR-ADMIN-ORDER-02 / 소유 BC: BC-04 / 종류: 조회 / 권한: 관리자 / 멱등성: 예
- 요청: 경로 `orderId`
- 응답 200: `AdminOrderResponse`
- 상태 전이: 해당 없음
- 에러: ER-01 / ER-02 / ER-05

### EP-27 POST `/api-admin/v1/points/charge`
- 근거 FR: FR-ADMIN-POINT-01 / 소유 BC: BC-03 / 종류: 명령 / 권한: 관리자 / 멱등성: 아니오
- 요청

| 위치 | 필드 | 타입 | 필수 | 제약 | 의미 | 출처 개념 |
|---|---|---|---|---|---|---|
| 바디 | `userId` | long | 예 | 존재하는 사용자 | 충전 대상 사용자 (ID만) | 사용자 (참조) |
| 바디 | `amount` | long | 예 | 정수 > 0, 표현 범위 안 | 충전액 | 운영자 충전 |

- 응답 200: `PointBalance` (대상 사용자의 충전 후 잔액)
- 상태 전이: 해당 없음
- 에러: ER-01 (요청자 없음 **또는** 바디 `userId`의 사용자 없음. `message`로 구분) / ER-02 / ER-09 (amount 오류. `userId` 누락·타입 오류는 ER-22) / ER-10

### EP-28 POST `/api-admin/v1/points/deduct`
- 근거 FR: FR-ADMIN-POINT-02 / 소유 BC: BC-03 / 종류: 명령 / 권한: 관리자 / 멱등성: 아니오
- 요청: EP-27과 같음 (`userId`, `amount`. 출처 개념: 사용자 참조, 운영자 차감)
- 응답 200: `PointBalance` (차감 후 잔액)
- 상태 전이: 해당 없음
- 에러: ER-01 (EP-27과 같음) / ER-02 / ER-09 / ER-11 (대상 잔액 < amount)

### 4-4. 에러 규약

| ER-ID | 코드 | HTTP | 메시지 (사용자 노출) | 근거 FR 실패 케이스 | 유형 | 상태 변화 |
|---|---|---|---|---|---|---|
| ER-01 | `USER_NOT_FOUND` | 404 | 사용자를 찾을 수 없습니다 | 공통 (모든 FR, ASM-01) / FR-ADMIN-POINT-01, 02 (대상 사용자) | 참조 | 없음 |
| ER-02 | `NOT_ADMIN` | 403 | 관리자만 사용할 수 있습니다 | 공통 (모든 관리자 FR, ASM-26) | 권한 | 없음 |
| ER-03 | `BRAND_NOT_FOUND` | 404 | 브랜드를 찾을 수 없습니다 | FR-BRAND-01 (없음·삭제됨) / FR-ADMIN-BRAND-03 / 04 (없음·삭제됨) / 05 (없음·이미 삭제됨) / FR-ADMIN-PRODUCT-02 | 참조 | 없음 |
| ER-04 | `PRODUCT_NOT_FOUND` | 404 | 상품을 찾을 수 없습니다 | FR-PRODUCT-02 / FR-LIKE-01 / FR-LIKE-02 / FR-ORDER-01 / FR-ORDER-02 (삭제됨) / FR-ADMIN-PRODUCT-03 / 04 / 05 / 06 | 참조 | 없음 |
| ER-05 | `ORDER_NOT_FOUND` | 404 | 주문을 찾을 수 없습니다 | FR-ORDER-02 / FR-ORDER-04 / FR-ADMIN-ORDER-02 | 참조 | 없음 |
| ER-06 | `NOT_OWNER` | 403 | 본인의 것만 조회·확정할 수 있습니다 | FR-LIKE-03 / FR-ORDER-02 / FR-ORDER-04 (ASM-09) | 권한 | 없음 |
| ER-07 | `INVALID_SORT` | 400 | 정렬 값이 올바르지 않습니다 | FR-PRODUCT-01 | 검증 | 없음 |
| ER-08 | `INVALID_PAGE` | 400 | 페이지 값이 올바르지 않습니다 | FR-PRODUCT-01 / FR-LIKE-03 / FR-ORDER-03 / FR-ADMIN-BRAND-01 / FR-ADMIN-PRODUCT-01 / FR-ADMIN-ORDER-01 | 검증 | 없음 |
| ER-09 | `INVALID_AMOUNT` | 400 | 금액이 올바르지 않습니다 | FR-POINT-01 (4건) / FR-POINT-03 / FR-ADMIN-POINT-01 / 02 | 검증 | 없음 |
| ER-10 | `BALANCE_LIMIT_EXCEEDED` | 409 | 잔액 한도를 초과합니다 | FR-POINT-01 / FR-ADMIN-POINT-01 | INV (INV-02) | 없음 |
| ER-11 | `INSUFFICIENT_POINT` | 409 | 포인트가 부족합니다 | FR-POINT-03 / FR-ORDER-02 / FR-ADMIN-POINT-02 | INV (INV-01) | 없음 |
| ER-12 | `EMPTY_ORDER_ITEMS` | 400 | 주문 품목이 없습니다 | FR-ORDER-01 | 검증 (INV-12) | 없음 |
| ER-13 | `INVALID_QUANTITY` | 400 | 수량이 올바르지 않습니다 | FR-ORDER-01 | 검증 (INV-07) | 없음 |
| ER-14 | `AMOUNT_OUT_OF_RANGE` | 400 | 주문 금액이 표현 범위를 초과합니다 | FR-ORDER-01 | 검증 (ASM-05) | 없음 |
| ER-15 | `ORDER_NOT_DRAFT` | 409 | 확정할 수 없는 주문입니다 | FR-ORDER-02 | ST (ST-03) | 없음 (CONFIRMED 유지) |
| ER-16 | `INSUFFICIENT_STOCK` | 409 | 재고가 부족합니다 | FR-ORDER-02 | INV (INV-03) | 없음 (DRAFT 유지) |
| ER-17 | `INVALID_BRAND` | 400 | 브랜드 정보가 올바르지 않습니다 | FR-ADMIN-BRAND-02 / 04 | 검증 (INV-14) | 없음 |
| ER-18 | `BRAND_HAS_PRODUCTS` | 409 | 상품이 남아 있는 브랜드는 삭제할 수 없습니다 | FR-ADMIN-BRAND-05 | INV (INV-10) | 없음 (ACTIVE 유지) |
| ER-19 | `INVALID_PRODUCT_NAME` | 400 | 상품 이름이 올바르지 않습니다 | FR-ADMIN-PRODUCT-02 / 04 | 검증 (INV-13) | 없음 |
| ER-20 | `INVALID_PRODUCT_PRICE` | 400 | 상품 가격이 올바르지 않습니다 | FR-ADMIN-PRODUCT-02 / 04 | 검증 (INV-13) | 없음 |
| ER-21 | `INVALID_STOCK` | 400 | 재고 수량이 올바르지 않습니다 | FR-ADMIN-PRODUCT-02 / 06 | 검증 (INV-03) | 없음 |
| ER-22 | `BAD_REQUEST` | 400 | 요청 형식이 올바르지 않습니다 | 없음 (요구사항 밖: JSON 파싱 불가, 바디 누락 등) | 검증 | 없음 |

메모
- 요구사항 2절의 실패 케이스 이름 21개(`USER_NOT_FOUND`, `NOT_ADMIN`, `BRAND_NOT_FOUND`, `PRODUCT_NOT_FOUND`, `ORDER_NOT_FOUND`, `NOT_OWNER`, `INVALID_SORT`, `INVALID_PAGE`, `INVALID_AMOUNT`, `BALANCE_LIMIT_EXCEEDED`, `INSUFFICIENT_POINT`, `EMPTY_ORDER_ITEMS`, `INVALID_QUANTITY`, `AMOUNT_OUT_OF_RANGE`, `ORDER_NOT_DRAFT`, `INSUFFICIENT_STOCK`, `INVALID_BRAND`, `BRAND_HAS_PRODUCTS`, `INVALID_PRODUCT_NAME`, `INVALID_PRODUCT_PRICE`, `INVALID_STOCK`)가 각각 ER- 하나에 있다. 재작명 없음.
- 상태 변화는 전부 "없음"이다(ASM-12: 실패 상태 없음). 따라서 5-4 예외 규칙 대상 FR은 없다.
- 경로·바디 ID의 형식 오류(long 아님)는 해당 `*_NOT_FOUND`로 나간다 (DR-01). 값의 타입 오류 중 요구사항이 이름을 준 것(`amount`, `quantity`, `stock`, `price`, `page`, `size`)은 그 이름으로, 준 적 없는 것은 ER-22.
- 매핑되지 않은 예외는 1-3 기본값대로 500 `INTERNAL_ERROR`. ER-로 세지 않는다.
- 동시성 ER 없음 (3-6 없음).

### 4-5. 계약 변경

1-3과 동일. 변경은 DR로만.

### 4-6. 완료 조건

- [x] 4-1 에러 코드 체계(도메인별)가 정해져 있고 근거 FR이 있다
- [x] EP 28개가 각각 정확히 하나의 근거 FR을 가진다
- [x] FR 28개가 각각 EP 하나를 가진다 (시스템 행위자 FR 없음)
- [x] 모든 EP의 소유 BC가 2-4 진입 BC와 같다
- [x] 모든 EP의 권한이 요구사항 1-2와 일치한다 (고객 13, 관리자 15)
- [ ] 모든 경로 리소스명이 소유 BC의 소유 개념이다 — **EP-06의 `users`만 참조 개념.** 원문 경로(CON-01)라 CON이 이긴다. EP-27·28은 경로를 바꿔 해소 (DR-20)
- [x] 모든 응답 필드의 출처가 소유 BC의 TB·`조합`·계산이다 (참조 개념은 ID만: `productId`, `userId`, `brandId`는 요청에서만 ID고 응답의 `brand`는 같은 BC)
- [x] 모든 요청의 참조 개념(사용자, 상품, 브랜드)이 ID만 받는다
- [x] 상태를 바꾸는 명령 EP(EP-10, 11, 15, 18, 20, 23)가 ST- 허용 전이를 명시한다
- [x] 요구사항 2절의 모든 실패 케이스가 정확히 하나의 ER-에 있다
- [x] ST-01(ER-03, 18), ST-02(ER-04), ST-03(ER-15) 각각 ER-를 가진다
- [x] LK- 없음
- [x] 모든 ER-의 HTTP가 1-3 고정 매핑을 따른다
- [x] 모든 ER-의 상태 변화가 요구사항 결과 상태(전부 "변화 없음"/"유지")와 일치한다
- [x] 금지어 없음 (클래스·DTO·프레임워크 이름 없음. TB는 출처 칸에서 ID로만)

5장으로 인계되는 것
- 4-3-0 공통 형태와 각 EP 요청·응답 → Dto. 필드명·타입·NULL 그대로
- 4-4 ER-01~22 → 도메인 예외와 `ApiControllerAdvice` 매핑
- 4-1 페이징(`page`, `size`, `Page<T>` 형태)과 봉투 → 한 곳에 구현

## 5. 객체 (Level 3)

가이드 5-1~5-5, 5-7의 규칙을 그대로 따른다. 이 장에 쓰는 것은 이 프로젝트에 적용한 이름(5-0), 5-4·5-5의 적용 결과, 5-6 BC 간 호출 표다. 클래스 다이어그램은 없다.

### 5-0. BC별 패키지와 클래스 (이름만)

패키지 `com.loopers.{interfaces.api, application, domain, infrastructure}.<bc>`. `<bc>`는 2-1의 영문 식별자.

| BC | `<bc>` | domain (Model / Service / Repository) | application (Facade) | interfaces.api |
|---|---|---|---|---|
| BC-01 사용자 | `user` | `UserModel` / `UserService` / `UserRepository` | 없음 (EP를 갖지 않는다. 다른 BC의 Facade가 `UserService`를 부른다) | 없음 |
| BC-02 카탈로그 | `catalog` | `BrandModel`, `ProductModel`, `ProductLikeModel` / `BrandService`, `ProductService`, `ProductLikeService` / `BrandRepository`, `ProductRepository`, `ProductLikeRepository` | `BrandFacade`, `ProductFacade`, `ProductLikeFacade` | `BrandV1*`, `ProductV1*`, `ProductLikeV1*`, `BrandAdminV1*`, `ProductAdminV1*` |
| BC-03 포인트 | `point` | `PointModel` / `PointService` / `PointRepository` | `PointFacade` | `PointV1*`, `PointAdminV1*` |
| BC-04 주문 | `order` | `OrderModel` (루트), `OrderItemModel` (포함, AG-06) / `OrderService` / `OrderRepository` | `OrderFacade` | `OrderV1*`, `OrderAdminV1*` |

`*` = `ApiSpec`, `Controller`, `Dto`. Repository는 AG 루트 단위(6개)만 있다. `OrderItemRepository`는 만들지 않는다 (5-3).

Facade public 메서드 ↔ FR (28:28)

| Facade | 메서드 → FR |
|---|---|
| `BrandFacade` | `getBrand` FR-BRAND-01 · `listBrandsForAdmin` FR-ADMIN-BRAND-01 · `createBrand` 02 · `getBrandForAdmin` 03 · `updateBrand` 04 · `deleteBrand` 05 |
| `ProductFacade` | `listProducts` FR-PRODUCT-01 · `getProduct` 02 · `listProductsForAdmin` FR-ADMIN-PRODUCT-01 · `createProduct` 02 · `getProductForAdmin` 03 · `updateProduct` 04 · `deleteProduct` 05 · `updateStock` 06 |
| `ProductLikeFacade` | `like` FR-LIKE-01 · `unlike` 02 · `listMyLikes` 03 |
| `PointFacade` | `charge` FR-POINT-01 · `getBalance` 02 · `refund` 03 · `chargeByAdmin` FR-ADMIN-POINT-01 · `deductByAdmin` 02 |
| `OrderFacade` | `createOrder` FR-ORDER-01 · `confirmOrder` 02 · `listMyOrders` 03 · `getMyOrder` 04 · `listOrdersForAdmin` FR-ADMIN-ORDER-01 · `getOrderForAdmin` 02 |

### 5-1 ~ 5-3. 계층·역할·애그리거트

가이드와 동일. 이 프로젝트에서 특히 지키는 것:

- `domain.order`는 `domain.catalog`·`domain.point`·`domain.user`를 import하지 않는다. `OrderItemModel`은 `productId`(Long)만 든다. 단가는 Facade가 `ProductService`에서 받은 값을 꺼내 `OrderModel.create(...)`에 넘긴다.
- `OrderItemModel`을 만드는 코드는 `OrderModel` 안에만 있다. 같은 상품 합산(ASM-11, INV-08)과 합계 계산(INV-06)도 거기서.
- 거부(AG 내) INV-의 위치: INV-01·02 `PointModel` / INV-03·11·13 `ProductModel` / INV-14 `BrandModel` / INV-06·07·08·09·12 `OrderModel` / INV-15 `UserModel` / INV-04·05 `ProductLikeService`(DR-05, 여러 Model에 걸친 같은 BC 규칙).
- INV-10(거부·FR 트랜잭션, DR-04)은 어느 Model도 직접 검증하지 않는다. `BrandFacade.deleteBrand`가 `ProductService.existsActiveByBrand`를, `ProductFacade.createProduct`가 `BrandService.getActive`를 같은 트랜잭션에서 부른다.
- 3-2 전이 메서드: `BrandModel.delete()`, `ProductModel.delete()`, `OrderModel.confirm(...)`. `deletedAt`·`status`에 setter 없음. 복구·`fail()` 메서드 없음.
- ID는 DB 자동 증가(DR-01). Model 생성자에서 ID를 만들지 않는다.

### 5-4. 실패 표현

전부 기본 규칙이다. 4-4의 ER-01~22 모두 상태 변화 "없음"이므로 예외 규칙(실패 상태 저장)에 해당하는 FR이 없다.

- 도메인 예외 하나(`CoreException`)에 `ErrorType`을 실어 던진다. `ErrorType`은 4-4의 코드 22개를 값으로 가지며 각 값이 HTTP 상태를 안다. 4-4에 없는 값을 추가하지 않는다.
- 요청자 식별 실패(ER-01)·관리자 아님(ER-02)은 `UserService`가 던진다. 나머지 참조 실패(ER-03·04·05)는 해당 BC의 Service, INV·ST 위반(ER-10·11·15·16·18)은 Model, 검증(ER-07·08·09·12·13·14·17·19·20·21)은 Model 생성자 또는 값 객체. ER-22와 500은 `ApiControllerAdvice` 기본 처리.

### 5-5. 트랜잭션

- 2-4가 전부 "즉시"이므로 Facade public 메서드 하나 = 트랜잭션 하나. 분리 없음.
- 조회 FR 15개(FR-BRAND-01, PRODUCT-01/02, LIKE-03, POINT-02, ORDER-03/04, ADMIN-BRAND-01/03, ADMIN-PRODUCT-01/03, ADMIN-ORDER-01/02, 그리고 요청자 식별만 하는 조회)는 읽기 전용 트랜잭션.
- `OrderFacade.confirmOrder`는 한 트랜잭션에서 `ProductService.deductStock`(품목 수만큼) → `PointService.deduct` → `OrderModel.confirm` 순으로 부른다. 어느 하나가 예외를 던지면 전부 롤백 (ASM-14, DR-08). 순서는 검사 비용이 싼 것부터가 아니라 요구사항 FR-ORDER-02 실패 케이스 순서(상품 삭제 → 재고 → 잔액)를 따른다.
- LK- 없음.

### 5-6. BC 간 호출 표

| 호출하는 Facade.메서드 | 근거 FR-ID | 호출받는 Service.메서드 | 목적 | 2-3 행 |
|---|---|---|---|---|
| 모든 Facade public 메서드 (28개) | 전부 (공통 사전 조건) | `UserService.getUser(requesterId)` — 관리자 FR은 `UserService.getAdmin(requesterId)` | 조회 | BC-01 → BC-02/03/04 |
| `PointFacade.chargeByAdmin`, `deductByAdmin` | FR-ADMIN-POINT-01, 02 | `UserService.getUser(targetUserId)` (대상 사용자 존재) | 조회 | BC-01 → BC-03 |
| `OrderFacade.createOrder` | FR-ORDER-01 | `ProductService.getActiveProducts(productIds)` → 존재·ACTIVE·가격 | 조회 | BC-02 → BC-04 |
| `OrderFacade.confirmOrder` | FR-ORDER-02 | `ProductService.getActiveProducts(productIds)` → 삭제 여부 재검증 | 조회 | BC-02 → BC-04 |
| `OrderFacade.confirmOrder` | FR-ORDER-02 | `ProductService.deductStock(productId, quantity)` (품목마다) | **변경** (DR-08) | BC-02 → BC-04 |
| `OrderFacade.confirmOrder` | FR-ORDER-02 | `PointService.deduct(userId, totalAmount)` | **변경** (DR-08) | BC-03 → BC-04 |

같은 BC 안의 Facade → Service 호출(`BrandFacade` → `ProductService`, `ProductFacade` → `BrandService`·`ProductLikeService` 등)은 BC 간이 아니므로 이 표에 없다. 2-3의 "표현 범위" 행은 상수 공유라 호출이 없다.

### 5-7. 테스트

가이드 표 그대로. 이 프로젝트의 대상 수:

| 대상 | 수 | 이름에 포함할 ID |
|---|---|---|
| FR 성공 경로 (Facade 통합) | 28 | `FR-XX` |
| FR 실패 케이스 (결과 상태까지) | 요구사항 2절 실패 케이스 행 전부 + 공통 2(`USER_NOT_FOUND`, `NOT_ADMIN`) | `FR-XX` + 실패 케이스 이름 |
| 거부 INV (Model/Service 단위) | 15 (INV-01~15. INV-10은 Facade 통합으로) | `INV-XX` |
| ST 금지 전이 | 3: ST-01 DELETED→ACTIVE, ST-02 DELETED→ACTIVE, ST-03 CONFIRMED→DRAFT / CONFIRMED→CONFIRMED | `ST-XX` |
| LK | 0 | — |
| ER (ApiSpec 수준, HTTP·코드) | 22 | `ER-XX` |

### 5-8. 완료 조건

코드 작성 후 검사한다. 문서 시점에는 전부 미확인.

- [ ] `domain.<A>` → `domain.<B>` import 없음
- [ ] `application.<A>` → `domain.<B>.Repository` import 없음
- [ ] `interfaces` → `domain`·`infrastructure` import 없음
- [ ] Facade public 메서드 28개 ↔ FR 28개, 주석에 FR-ID
- [ ] EP 28개 ↔ ApiSpec 메서드 28개
- [ ] 거부(AG 내) INV 14개가 Model/Service 안에서 검증됨
- [ ] 전이 메서드 3개만 존재, setter 없음
- [ ] `ApiControllerAdvice`가 ER-01~22 매핑, 매핑 없는 예외 밖으로 안 나감
- [ ] 5-4 DR 대상 FR 없음 (해당 없음)
- [ ] 5-6 표 = 코드의 BC 간 호출
- [ ] Repository 6개 (AG 루트 단위)
- [ ] 5-7 대상 전부 테스트 있음

---

## 부록 A. 설계 판단 기록과 열린 질문

### A-1. 결정

| DR-ID | 장 | 관련 ID | 결정 | 버린 대안 | 이유 | 되돌릴 조건 | 요구사항 반영 |
|---|---|---|---|---|---|---|---|
| DR-01 | 1 | CON-03, ASM-01, ASM-26, TB-01~07 | 모든 테이블의 PK를 Long 자동 증가로 한다. `X-USER-ID` 값과 경로의 모든 `{…Id}`는 이 PK다 | (a) UUIDv7 (처음 검토했던 안) (b) 사용자만 UUID, 나머지 정수 (c) 헤더 형식 오류를 `INVALID_USER_ID`로 분리 | 팀 제약으로 확정. ID 생성은 DB가 맡으므로 코드에 생성 책임이 없다 | 팀 제약이 바뀌면 재검토. 그때 TB 컬럼 타입과 4장 요청 필드 타입이 함께 바뀐다 | 없음. ASM-01 유지 — `X-USER-ID`는 클라이언트가 아니라 시스템(앞단)이 넣는 값이므로 누락·형식 오류·없는 사용자를 나눌 실익이 없다. 셋 다 `USER_NOT_FOUND`. 같은 결로 경로·바디의 ID 형식 오류도 "그 ID가 가리키는 대상이 없다"로 보아 해당 `*_NOT_FOUND`로 처리한다(4장). 요구사항 6장 5번 항목은 이 결정으로 닫힌다 |
| DR-02 | 2 | 좋아요, 좋아요 수, INV-05, FR-PRODUCT-01, FR-LIKE-01 | 좋아요·좋아요 수를 BC-02 카탈로그의 소유 개념으로 둔다. 별도 좋아요 BC를 만들지 않는다 | 좋아요 BC 분리 | 분리하면 카탈로그→좋아요(상품 존재 확인)와 좋아요→카탈로그(좋아요 수 조회)가 동시에 생겨 2-3 순환이 된다. 또 FR-PRODUCT-01의 `likes_desc` 정렬이 두 BC에 걸친 정렬·페이징이 되어 3-7-A에서 설계 불가로 떨어진다. 같은 BC 안에서는 AG-03과 AG-04를 한 조회로 정렬할 수 있다 | 좋아요가 상품 외 대상(브랜드 등)으로 확장되거나 팀이 갈리면 재검토한다 | 없음 |
| DR-03 | 2 | 재고, INV-03, FR-ORDER-02, FR-ADMIN-PRODUCT-06 | 재고를 상품 AG(AG-03)의 포함 개념으로 둔다 | 재고를 별도 AG 또는 별도 BC로 | INV-03은 재고 단독 규칙이라 어디든 갈 수 있지만, 원문이 "상품은 이름·가격·재고를 가진다"로 못 박았고 재고를 참조하는 FR이 전부 상품 ID로 접근한다. 분리하면 FR-ORDER-02의 변경 AG가 하나 더 늘 뿐 얻는 게 없다 | 재고가 창고·위치 단위로 나뉘거나 상품 수정과 재고 변경의 잠금 경합이 문제되면 재검토한다 | 없음 |
| DR-04 | 2 | INV-10, AG-02, AG-03, FR-ADMIN-BRAND-05, FR-ADMIN-PRODUCT-02 | 브랜드와 상품을 별개 AG로 두고 INV-10을 거부(FR 트랜잭션)로 배치한다. 두 FR은 변경 AG 하나 + 다른 AG 조회를 같은 트랜잭션에서 수행해 검증한다 | (a) 브랜드 AG 안에 상품을 포함 (b) 최종 일관성 | (a)는 상품이 3-2 상태 전이를 가진 루트 후보이고, 상품 하나를 수정할 때 브랜드와 소속 상품 전체를 읽게 된다. (b)는 원문이 "삭제된 브랜드에 상품 생성 불가"를 거절로 적었으므로 완화가 아니다. 가이드의 거부(FR 트랜잭션)는 "다중 AG 변경"을 전제하지만 여기서는 한쪽이 조회다. 문자에서 벗어나는 지점이며, 단일 요청 안에서는 트랜잭션 원자성으로 지켜지고 동시 요청 사이의 창은 OQ-02 | 요구사항에 동시성 비기능이 추가되면 3-6에서 잠금 방식을 정하고 이 DR을 갱신한다 | 없음 |
| DR-05 | 2 | INV-04, AG-04, ASM-06, FR-LIKE-01 | INV-04(같은 쌍 최대 하나)는 루트 Model 하나가 검증할 수 없으므로 같은 BC의 Service가 "있으면 반환, 없으면 생성"으로 지키고, 3장에서 (사용자 ID, 상품 ID) 고유 제약을 함께 둔다 | 사용자별 좋아요 묶음을 AG 루트로 | ASM-06이 멱등이라 거절이 아니라 조회 후 생성이면 충분하다. 묶음 루트는 좋아요 수(상품 기준 집계)와 방향이 맞지 않는다 | ASM-06이 거절(`ALREADY_LIKED`)로 뒤집히면 검증이 예외를 던지도록 바꾸되 위치는 같다 | 없음 |
| DR-06 | 2 | AG-01, ASM-26, 관리자 | 사용자 AG를 두되 근거 INV가 없으므로 요구사항 3-1에 INV-15 "관리자는 관리자 권한을 가진 사용자다(관리자 ⊂ 사용자)"를 추가하도록 피드백한다 | INV 없이 AG 유지 | 가이드 "근거 INV 없는 AG는 자격 없음"과 "모든 TB는 어느 AG 소속"이 충돌한다. ASM-26이 이미 이 규칙을 말하고 있으므로 INV로 올리는 것이 0-4에 맞다 | ASM-26이 "관리자를 별개 대상"으로 뒤집히면 AG-01을 둘로 나누고 INV-15를 다시 쓴다 | 요구사항 3-1에 INV-15 "관리자는 관리자 권한을 가진 사용자다" 추가 (확정) |
| DR-07 | 2 | 잔액, AG-05, ASM-24, ASM-25, 요구사항 1-3 | 잔액을 단일 저장값으로 둔다. 포인트 이력·충전 건 테이블은 만들지 않는다 | 충전·차감마다 이력 행을 남기고 잔액을 합으로 계산 | 이력을 읽는 FR이 없다(가이드 3-5 "읽는 FR 없으면 만들지 않는다"). 요구사항 1-3이 요구한 "여지"는 AG 경계로 남긴다: 이력·충전 건이 들어와도 AG-05의 포함 개념이지 새 AG가 아니다 | ASM-25가 뒤집혀 사유 기록이 필요해지거나 포인트 이력 조회 FR이 생기면 AG-05에 이력 포함 개념을 추가한다 | 없음 |
| DR-08 | 2 | FR-ORDER-02, AG-03, AG-05, AG-06, ASM-14, INV-01, INV-03 | FR-ORDER-02는 품목별 상품 AG(N개)·포인트 AG·주문 AG를 한 트랜잭션에서 변경한다 | (a) 재고 차감과 잔액 차감을 별도 트랜잭션으로(최종) | ASM-14가 전부 또는 전무를 요구한다. 최종 일관성은 이벤트·재시도 없이 보상 호출뿐이고 보상 실패 시 상태를 정의할 수 없다 | 주문 확정 지연이나 재고 잠금 경합이 측정으로 확인되면 재검토한다 | 없음 |
| DR-09 | 2 | 사용자, ASM-19, FR-ADMIN-ORDER-01/02 | 원문 "구매자"는 주문 BC에서의 사용자(주문의 주인)를 가리키는 말로 본다. 새 개념을 만들지 않고 용어 충돌 표에만 적는다 | 용어집에 "구매자" 별도 등재 | 별개 속성이 없다. 이름만 다르다 | 구매자에게 주문 외 속성(배송지 등)이 붙으면 소유 개념으로 올린다 | 요구사항 1-1에 "구매자 = 주문을 만든 사용자" 별칭 한 줄 추가 권고 (선택) |

| DR-10 | 3 | TB-04, TB-06 | 테이블명 `product_like`, `orders`. 소유 개념 이름(좋아요, 주문)과 어긋나는 물리 이름 | `like`, `order` | 둘 다 SQL 예약어라 모든 DB에서 따옴표가 필요하다. 개념 이름은 2-1과 5장 Model 이름에서 유지된다 | 없음 | 없음 |
| DR-11 | 3 | ASM-03, ASM-04, ASM-05, ASM-20, INV-02, INV-13, INV-14 | 원문에 없는 값을 확정한다. 표현 범위 = 64비트 부호 있는 정수(0 ~ 9,223,372,036,854,775,807). 브랜드 이름 1~100자, 상품 이름 1~200자. 재고·수량은 32비트 정수(0 또는 1 ~ 2,147,483,647). 관리자 주문 목록의 구매자 묶음 순서 = 사용자 ID 오름차순 | 표현 범위를 업무 상한(예: 1억)으로 / 이름 상한 255 | 금액은 64비트면 오버플로 검사가 타입 경계와 일치해 별도 상수가 필요 없다. 이름 상한은 화면·저장에 무리 없는 값. 묶음 순서는 자동 증가 ID라 사용자 ID 순 = 사용자 생성 순이 되어 안정적이고 별도 정렬 컬럼이 필요 없다 | 업무 상한이 정해지거나 화면 요구가 생기면 값을 바꾼다. 값만 바뀌고 구조는 안 바뀐다 | ASM-03·04·05에 값 확정 표시 `(설계 결정 DR-11)`, ASM-20에 묶음 순서 한 줄 추가 |
| DR-12 | 3 | TB-05, TB-01, FR-POINT-02, FR-ADMIN-POINT-01 | 포인트 계정(TB-05)은 사용자와 1:1이며 fixture에서 사용자와 함께 잔액 0으로 준비한다. 런타임에 생성하지 않는다 | (a) 첫 충전·조회 때 없으면 생성 (b) 사용자 PK를 포인트 PK로 겸용 | (a)는 조회 FR(FR-POINT-02)에 쓰기가 섞인다. (b)는 공통 컬럼 규칙(모든 테이블 자동 증가 `id`)에 어긋난다. 사용자 가입이 범위 밖이라 fixture가 둘을 같이 만드는 게 가장 단순하다 | 사용자 가입 FR이 들어오면 가입 트랜잭션에서 포인트 계정을 함께 만드는 것으로 바꾼다 | 없음 |
| DR-13 | 3 | TB-06, TB-07, INV-06, INV-09, ASM-13, FR-ORDER-03 | 합계(`total_amount`)는 생성 시 계산해 저장한다. 항목 금액은 저장하지 않는다. 결제액(`paid_amount`)과 확정 시각(`confirmed_at`)을 결제 결과로 두고 별도 테이블은 만들지 않는다 | (a) 합계도 매번 품목에서 집계 (b) 결제 결과를 별도 테이블로 | 주문 품목은 생성 후 불변(주문 수정 범위 밖)이라 저장된 합계가 어긋날 갱신 경로가 없고, 내 주문 목록(FR-ORDER-03)이 주문마다 합계를 보여줘 집계를 피할 가치가 있다. 항목 금액은 같은 행의 두 컬럼 곱이라 저장 이득이 없다. 결제 결과는 필드 둘뿐이라 테이블로 뺄 이유가 없다 | 주문 수정이 범위에 들어오면 합계 갱신 책임을 루트 Model에 두고 다시 검토한다. 결제 결과에 외부 결제 정보가 붙으면 테이블로 뺀다 | 없음 |
| DR-14 | 3 | 3-7-A 예상 빈도 | 비기능이 없으므로 빈도는 상대 추정: 고객 상품 조회 높음, 그 외 고객 FR 중간(환불만 낮음), 관리자 FR 낮음 | 전부 미정 | 인덱스 생성 판단(3-7-B)에 기준이 필요하다. 고객 목록·상세가 가장 많이 불리고 관리자 행위는 드물다는 통상 가정 | 부하 측정치가 생기면 값을 바꾸고 3-7-B를 다시 본다 | 없음 |
| DR-15 | 3 | IX-01, IX-02, IX-03, IX-04, IX-06 | 복합 인덱스 컬럼 순서. IX-01은 `user_id` 먼저(FR-LIKE-03이 사용자로 거르고, 쌍 조회는 두 컬럼 다 씀). 상품 기준 집계는 IX-02가 따로 맡는다. IX-03·IX-04는 `status`(등호) → 정렬 키 → `id`(동률 보조). IX-06은 `user_id`(등호) → `created_at` → `id` | IX-01을 `product_id` 먼저로 하고 IX-02 대신 `user_id` 단독 | 등호 조건을 앞에, 정렬 키를 뒤에 두면 필터 후 정렬을 인덱스 순서로 읽는다. 좋아요는 사용자 기준 목록과 상품 기준 집계가 둘 다 있어 인덱스 둘이 필요하다 | `likes_desc`나 좋아요 집계가 병목이면 좋아요 수 계산값 컬럼(3-2 규칙상 DR 필요)을 검토한다 | 없음 |
| DR-16 | 3 | TB-01~07, ST-01, ST-02, ASM-02, ASM-15 | 모든 테이블에 공통 컬럼 `created_at`·`updated_at`·`deleted_at`을 둔다(팀 제약). 브랜드·상품의 상태(요구사항 3-2 ACTIVE/DELETED)는 별도 `status` 컬럼 없이 `deleted_at`의 NULL 여부로 표현한다. 고객 조회는 `deleted_at IS NULL`만, 관리자 조회는 전부. 삭제 FR이 없는 테이블의 `deleted_at`은 항상 NULL이며 조회 조건에 쓰지 않는다 | `deleted_at` + `status` 둘 다 / 상태 컬럼만 두고 `deleted_at` 미사용 | 공통 컬럼이 제약이므로 `deleted_at`은 어차피 존재한다. 그 위에 `status`를 또 두면 같은 사실이 두 곳에 산다. 삭제 시각이 곧 전이 시각이라 ST-의 정보도 잃지 않는다 | 브랜드·상품에 DELETED 외 상태(판매 중지 등)가 생기면 `status` 컬럼을 추가하고 `deleted_at`은 시각만 담는 시스템 컬럼으로 되돌린다 | 없음 |
| DR-17 | 3 | TB-04, FR-LIKE-01, FR-LIKE-02, INV-04, ASM-06 | 좋아요 취소는 물리 삭제. `deleted_at`은 공통 컬럼으로 존재하지만 쓰지 않는다 | 논리 삭제 후 재등록 시 `deleted_at`을 NULL로 되돌림 | (`user_id`, `product_id`) 고유 제약(INV-04)과 멱등 등록(ASM-06)을 그대로 두려면 취소한 행이 남아 있으면 안 된다. 논리 삭제로 가면 "있으면 성공"이 "있고 삭제 안 됐으면 성공, 있고 삭제됐으면 복구"로 갈라진다. 좋아요 이력을 읽는 FR이 없다 | 좋아요 이력·통계가 범위에 들어오면 논리 삭제 + 복구로 바꾸고 고유 제약을 유지한다 | 없음 |
| DR-18 | 4 | ER-01, ER-06, ASM-01, ASM-09 | 요청자 식별 실패 `USER_NOT_FOUND`는 404(참조 없음). 소유자 아님 `NOT_OWNER`는 403으로 노출한다(존재를 숨기지 않음) | `USER_NOT_FOUND`를 401로 / `NOT_OWNER`를 404로 숨김 | 1-3 고정 매핑에 401이 없고, 헤더 값이 가리키는 사용자가 없는 것은 문자 그대로 참조 실패다. ASM-09가 노출을 기본으로 정했다 | 인증(자격 증명 검증)이 범위에 들어오면 401을 매핑에 추가하고 ER-01을 재분류한다. ASM-09가 뒤집히면 ER-06을 `*_NOT_FOUND`로 합친다 | 없음 |
| DR-19 | 4 | ASM-20, EP-02·06·12·14·19·25 | 페이징 확정: `page` 0부터 기본 0, `size` 1~100 기본 20, 페이지 정보(`items`·`page`·`size`·`totalCount`)는 `data` 안 | `meta`에 `totalCount` / 1부터 시작 / 상한 없음 | `meta`는 result·errorCode·message를 담는 공통 봉투라 목록 전용 값을 섞지 않는다. 0부터 시작은 오프셋 계산이 그대로다. 상한 100은 한 페이지 응답 크기 제한 | 무한 스크롤(커서)이 필요해지면 재검토 | ASM-20에 기본값·상한 확정 표시 `(설계 결정 DR-19)` |
| DR-20 | 4 | CON-01, ASM-23, EP-06, EP-27, EP-28 | EP-27·28 경로를 ASM-23의 `/api-admin/v1/users/{userId}/points/{charge|deduct}`에서 `/api-admin/v1/points/{charge|deduct}` + 바디 `userId`로 바꾼다. EP-06 `/users/{userId}/likes`는 원문 경로(CON-01)라 그대로 둔다 | ASM-23 경로 유지 (EP-06과 같은 모양) | 리소스 `points`는 BC-03 소유 개념이고 깊이 1단계라 4-2 규칙을 만족한다. ASM-23이 "설계에서 바꿔도 된다"고 열어뒀다. EP-06은 CON이 이기므로 남는 이탈은 하나뿐 | 원문이 관리자 포인트 경로를 정하면 그것을 따른다 | ASM-23의 경로를 위와 같이 고치고 `(설계 피드백 DR-20)` 표시 |
| DR-21 | 4 | 4-3-0, EP-04, EP-05, EP-18, EP-23 | 공통 응답 형태를 4-3-0에 한 번 정의하고 EP 블록에서 이름으로 참조한다. FR 출력이 없는 명령(좋아요 등록·취소, 브랜드·상품 삭제)은 200 + `data` null | EP마다 응답 표 반복 / 삭제·좋아요 응답에 대상 요약 반환 | 같은 형태를 28번 반복하면 불일치가 생긴다. 출력 없는 FR에 필드를 만들면 "근거 못 쓰는 필드" | 클라이언트가 좋아요 후 좋아요 수를 바로 필요로 하면 EP-04 응답에 `likeCount`를 추가하고 FR-LIKE-01 출력에 반영한다 | 없음 |
| DR-22 | 4 | OQ-04, FR-PRODUCT-01, FR-PRODUCT-02, ProductSummary | 고객 상품 응답에 재고를 넣지 않는다. 재고 부족은 확정 시 `INSUFFICIENT_STOCK`으로만 알린다 | `stock` 또는 `soldOut` 필드 추가 | 요구사항 FR-PRODUCT-01/02 출력에 재고가 없다. 설계가 임의로 넣을 수 없다 | 요구사항 출력에 재고가 추가되면 `ProductSummary`에 필드를 넣는다 | 없음 |

### A-2. 열린 질문

| OQ-ID | 관련 ID | 질문 | 상태 |
|---|---|---|---|
| OQ-01 | FR-ORDER-02, BC-01, BC-02, BC-03 | 참여 BC가 3개(사용자·카탈로그·포인트)라 규칙상 자동 등록. 다만 셋 중 사용자는 모든 FR의 공통 식별 절차다. 이를 참여 BC 수에서 제외하는 규칙을 DR로 두고 닫을지, 경계 재조정 대상으로 볼지 | 열림 |
| OQ-02 | INV-10, DR-04 | 브랜드 삭제와 그 브랜드 상품 생성이 동시에 오면 INV-10이 깨질 수 있다. 요구사항에 동시성 비기능이 없어 3-6에 넣을 수 없다. 요구사항에 비기능을 추가할지(→ 3-6 잠금), 이 창을 허용할지 | 열림 — 이번 요구사항은 동시성을 다루지 않기로 결정. 창을 허용하고 DR-04의 이탈 기록을 유지한다. 비기능이 추가되는 시점에 재개 |
| OQ-03 | FR-ORDER-02, INV-01, INV-03 | 같은 상품에 대한 동시 확정, 같은 사용자의 동시 확정에서 재고·잔액이 음수가 될 수 있다. 위와 같은 이유로 3-6 대상이 아니다. 요구사항 FR-ORDER-02에 비기능 "동시 요청 하에서도 INV-01·INV-03 유지"를 추가할지 | 열림 — 위와 같은 결정. 비기능이 추가되는 시점에 재개 |
| OQ-04 | FR-PRODUCT-01/02, ProductSummary | 고객 상품 응답에 재고(또는 품절 여부)를 넣을지. 요구사항 출력에 없어 뺐지만, 없으면 고객이 확정 시점에야 `INSUFFICIENT_STOCK`을 본다. 넣으려면 FR-PRODUCT-01/02 출력에 재고를 추가해야 한다 | DR-22로 닫힘 |

## 부록 B. 추적성 매트릭스

| FR-ID | 진입 BC | 변경 AG | 쓰는 TB | EP | ER (실패 케이스별) | 테스트 |
|---|---|---|---|---|---|---|
| FR-BRAND-01 | BC-02 | 없음 | 없음 | EP-01 | ER-01, ER-03 | `FR-BRAND-01`, `FR-BRAND-01_BRAND_NOT_FOUND` |
| FR-PRODUCT-01 | BC-02 | 없음 | 없음 | EP-02 | ER-01, ER-07, ER-08 | `FR-PRODUCT-01` (+ sort 3종), `_INVALID_SORT`, `_INVALID_PAGE` |
| FR-PRODUCT-02 | BC-02 | 없음 | 없음 | EP-03 | ER-01, ER-04 | `FR-PRODUCT-02`, `_PRODUCT_NOT_FOUND` |
| FR-LIKE-01 | BC-02 | AG-04 | TB-04 | EP-04 | ER-01, ER-04 | `FR-LIKE-01` (+ 멱등), `_PRODUCT_NOT_FOUND`, `INV-04`, `INV-05` |
| FR-LIKE-02 | BC-02 | AG-04 | TB-04 | EP-05 | ER-01, ER-04 | `FR-LIKE-02` (+ 없는 관계, 삭제된 상품), `_PRODUCT_NOT_FOUND` |
| FR-LIKE-03 | BC-02 | 없음 | 없음 | EP-06 | ER-01, ER-06, ER-08 | `FR-LIKE-03` (+ 삭제 상품 제외), `_NOT_OWNER`, `_INVALID_PAGE` |
| FR-POINT-01 | BC-03 | AG-05 | TB-05 | EP-07 | ER-01, ER-09, ER-10 | `FR-POINT-01`, `_INVALID_AMOUNT` ×4, `_BALANCE_LIMIT_EXCEEDED`, `INV-01`, `INV-02` |
| FR-POINT-02 | BC-03 | 없음 | 없음 | EP-08 | ER-01 | `FR-POINT-02` |
| FR-POINT-03 | BC-03 | AG-05 | TB-05 | EP-09 | ER-01, ER-09, ER-11 | `FR-POINT-03`, `_INVALID_AMOUNT`, `_INSUFFICIENT_POINT` |
| FR-ORDER-01 | BC-04 | AG-06 | TB-06, TB-07 | EP-10 | ER-01, ER-12, ER-04, ER-13, ER-14 | `FR-ORDER-01` (+ 합산), `_EMPTY_ORDER_ITEMS`, `_PRODUCT_NOT_FOUND`, `_INVALID_QUANTITY`, `_AMOUNT_OUT_OF_RANGE`, `INV-06`~`INV-08`, `INV-12` |
| FR-ORDER-02 | BC-04 | AG-03, AG-05, AG-06 | TB-03, TB-05, TB-06 | EP-11 | ER-01, ER-05, ER-06, ER-15, ER-04, ER-16, ER-11 | `FR-ORDER-02`, `_ORDER_NOT_FOUND`, `_NOT_OWNER`, `_ORDER_NOT_DRAFT`, `_PRODUCT_NOT_FOUND`, `_INSUFFICIENT_STOCK`, `_INSUFFICIENT_POINT` (각각 롤백 검증), `INV-09`, `ST-03` |
| FR-ORDER-03 | BC-04 | 없음 | 없음 | EP-12 | ER-01, ER-08 | `FR-ORDER-03`, `_INVALID_PAGE` |
| FR-ORDER-04 | BC-04 | 없음 | 없음 | EP-13 | ER-01, ER-05, ER-06 | `FR-ORDER-04`, `_ORDER_NOT_FOUND`, `_NOT_OWNER` |
| FR-ADMIN-BRAND-01 | BC-02 | 없음 | 없음 | EP-14 | ER-01, ER-02, ER-08 | `FR-ADMIN-BRAND-01` (+ 삭제 포함), `_NOT_ADMIN`, `_INVALID_PAGE` |
| FR-ADMIN-BRAND-02 | BC-02 | AG-02 | TB-02 | EP-15 | ER-01, ER-02, ER-17 | `FR-ADMIN-BRAND-02`, `_INVALID_BRAND`, `INV-14` |
| FR-ADMIN-BRAND-03 | BC-02 | 없음 | 없음 | EP-16 | ER-01, ER-02, ER-03 | `FR-ADMIN-BRAND-03` (+ 삭제된 것 조회), `_BRAND_NOT_FOUND` |
| FR-ADMIN-BRAND-04 | BC-02 | AG-02 | TB-02 | EP-17 | ER-01, ER-02, ER-03, ER-17 | `FR-ADMIN-BRAND-04`, `_BRAND_NOT_FOUND` ×2, `_INVALID_BRAND` |
| FR-ADMIN-BRAND-05 | BC-02 | AG-02 | TB-02 | EP-18 | ER-01, ER-02, ER-03, ER-18 | `FR-ADMIN-BRAND-05`, `_BRAND_NOT_FOUND` ×2, `_BRAND_HAS_PRODUCTS`, `INV-10`, `ST-01` |
| FR-ADMIN-PRODUCT-01 | BC-02 | 없음 | 없음 | EP-19 | ER-01, ER-02, ER-08 | `FR-ADMIN-PRODUCT-01`, `_INVALID_PAGE` |
| FR-ADMIN-PRODUCT-02 | BC-02 | AG-03 | TB-03 | EP-20 | ER-01, ER-02, ER-03, ER-19, ER-20, ER-21 | `FR-ADMIN-PRODUCT-02`, `_BRAND_NOT_FOUND`, `_INVALID_PRODUCT_NAME`, `_INVALID_PRODUCT_PRICE`, `_INVALID_STOCK`, `INV-10`, `INV-11`, `INV-13` |
| FR-ADMIN-PRODUCT-03 | BC-02 | 없음 | 없음 | EP-21 | ER-01, ER-02, ER-04 | `FR-ADMIN-PRODUCT-03`, `_PRODUCT_NOT_FOUND` |
| FR-ADMIN-PRODUCT-04 | BC-02 | AG-03 | TB-03 | EP-22 | ER-01, ER-02, ER-04, ER-19, ER-20 | `FR-ADMIN-PRODUCT-04` (+ 기존 주문 단가 불변), `_PRODUCT_NOT_FOUND` ×2, `_INVALID_PRODUCT_NAME`, `_INVALID_PRODUCT_PRICE` |
| FR-ADMIN-PRODUCT-05 | BC-02 | AG-03 | TB-03 | EP-23 | ER-01, ER-02, ER-04 | `FR-ADMIN-PRODUCT-05` (+ 좋아요·주문 품목 유지), `_PRODUCT_NOT_FOUND` ×2, `ST-02` |
| FR-ADMIN-PRODUCT-06 | BC-02 | AG-03 | TB-03 | EP-24 | ER-01, ER-02, ER-04, ER-21 | `FR-ADMIN-PRODUCT-06`, `_PRODUCT_NOT_FOUND` ×2, `_INVALID_STOCK`, `INV-03` |
| FR-ADMIN-ORDER-01 | BC-04 | 없음 | 없음 | EP-25 | ER-01, ER-02, ER-08 | `FR-ADMIN-ORDER-01` (+ 구매자 묶음), `_INVALID_PAGE` |
| FR-ADMIN-ORDER-02 | BC-04 | 없음 | 없음 | EP-26 | ER-01, ER-02, ER-05 | `FR-ADMIN-ORDER-02`, `_ORDER_NOT_FOUND` |
| FR-ADMIN-POINT-01 | BC-03 | AG-05 | TB-05 | EP-27 | ER-01, ER-02, ER-09, ER-10 | `FR-ADMIN-POINT-01`, `_USER_NOT_FOUND`, `_INVALID_AMOUNT`, `_BALANCE_LIMIT_EXCEEDED` |
| FR-ADMIN-POINT-02 | BC-03 | AG-05 | TB-05 | EP-28 | ER-01, ER-02, ER-09, ER-11 | `FR-ADMIN-POINT-02`, `_USER_NOT_FOUND`, `_INVALID_AMOUNT`, `_INSUFFICIENT_POINT` |

공통 실패(`USER_NOT_FOUND`, `NOT_ADMIN`)는 ER 칸에 전부 적었고 테스트는 Facade마다 하나씩(중복 생략). `INV-15`는 `UserService.getAdmin` 단위 테스트. 빈 칸 없음.