# 주문 할인 계약

## 1. 기존 API 관찰 결과

`ExampleV1ApiE2ETest`의 기존 동작과 `ContractClassificationTest`의 실제 HTTP 요청을 기준으로 기록한다.

| 입력 | HTTP status | `meta.result` | `meta.errorCode` | `data` |
| --- | ---: | --- | --- | --- |
| 존재하는 숫자 ID | `200 OK` | `SUCCESS` | 없음 (`null`) | 있음 |
| 숫자가 아닌 ID (`abc`) | `400 BAD_REQUEST` | `FAIL` | `BAD_REQUEST` | 없음 (`null`) |
| 존재하지 않는 숫자 ID | `404 NOT_FOUND` | `FAIL` | `NOT_FOUND` | 없음 (`null`) |
| 미매핑 URL | `404 NOT_FOUND` | `FAIL` | `NOT_FOUND` | 없음 (`null`) |

관찰 결과, 존재하지 않는 리소스와 미매핑 URL은 현재 동일한 `404 NOT_FOUND`와 `NOT_FOUND` 오류 코드로 응답한다.
이 구분을 외부 계약에서 유지할지는 제품 질문으로 남긴다.

## 2. 최소 입력

### 확인된 제품 약속

> 구매자는 주문 확정 전에 보유 쿠폰을 적용할 수 있고, 확정된 할인 금액은 이후에도 유지됩니다.

출처: Week 1 과제에서 정해진 확인된 제품 약속

### 실습 장면의 조건

이번 과제에서는 다음 조건을 사용한다.

- 한 주문에는 쿠폰 한 장만 적용한다.
- `finalAmount = originalAmount - discountAmount`로 계산한다.
- 같은 요청을 다시 보내면 기존 결과를 재사용한다.
- 쿠폰 만료 여부는 요청 시작 시각을 기준으로 판단한다.

출처: Week 1 과제에서 제공한 실습 장면의 조건

위 조건은 이번 실습을 위한 조건이며, 실제 제품 정책으로 확정된 내용과는 구분한다.

## 3. 아직 정하지 않은 내용

- 실제 제품 담당자에게 확인할 질문
- API 오류의 의미와 표현 방식
- 각 규칙을 맡을 책임과 내부 경계
- 비교할 설계 대안과 선택 이유

출처: Week 1 과제를 진행하는 학습자가 정할 것