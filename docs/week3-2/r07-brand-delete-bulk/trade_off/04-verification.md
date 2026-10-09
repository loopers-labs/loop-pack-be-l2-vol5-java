# 검증 방식

[← 전체 선택 현황](total_trade_off.md)

## 판단할 문제

구조가 바뀌면서 R01의 삭제 전용 조회 잠금 테스트(`BrandFindForDeletionLockIntegrationTest`, slow)의 대상이 사라진다. 가벼운 테스트 원칙 안에서 무엇을 자동 테스트로, 무엇을 수동 측정으로 확인할지 정한다.

> **채택 — 기능·롤백은 자동 테스트, 잠금 범위는 로컬 대량 데이터 수동 측정(Q6 i)**
>
> - 도메인: `Brand.delete()`가 브랜드만 삭제 상태로 바꾼다(기존 `BrandTest` 수정).
> - 서비스 단위(Mockito): 브랜드 저장 후 `deleteAllByBrandId` 호출, 없거나 이미 삭제된 브랜드면 상품 삭제 미호출. 등록은 `findByIdForShare`를 쓴다.
> - 저장소 통합: 해당 브랜드 활성 상품만 삭제·`updated_at` 갱신, 다른 브랜드·이미 삭제된 상품 불변.
> - 롤백 통합: `DeleteBrandRollbackIntegrationTest`를 상품 일괄 삭제 단계 실패로 바꿔 브랜드·상품 모두 롤백을 확인.
> - `BrandFindForDeletionLockIntegrationTest`는 대상 메서드가 사라져 삭제한다. 잠금 범위는 R04 EXPLAIN 스크립트의 대량 데이터로 `performance_schema.data_locks`를 세어 `result.md`에 기록한다.
>
> 대신 등록과 삭제의 잠금 순서(브랜드 `FOR SHARE`/`FOR UPDATE`)는 자동 동시성 테스트로 검증하지 않는다.

## 장단점 비교

| 기준 | **(i) 수동 측정** | (ii) slow 동시성 테스트 |
|---|---|---|
| 회귀 감지 | 없음(문서 기록) | 등록 대기·거절을 자동 확인 |
| 테스트 시간 | 늘지 않음 | 별도 커넥션·대기 시간만큼 증가 |
| 잠금 범위 증명 | `data_locks` 행 수로 직접 확인 | 범위는 확인하지 않음 |

## 옵션별 판단

> **채택 — (i) 수동 측정:** 가벼운 테스트 원칙. 잠금 범위라는 이번 변경의 핵심은 수동 측정이 더 직접적이다.

> **미채택 — (ii) slow 동시성 테스트:** 비용 대비 이번 변경의 핵심(잠금 범위)을 보여주지 못한다.
