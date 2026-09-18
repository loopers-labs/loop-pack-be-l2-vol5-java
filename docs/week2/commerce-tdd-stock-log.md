# 상품 재고 TDD 실행 기록

2026-09-18에 수행한 재고 차감과 최종 수량 설정 규칙의 구현·검증 기록이다. [전체 설계](commerce-erd-draft.md)의 재고 비음수 조건과 [TDD 계획](commerce-tdd-plan.md)의 STOCK-01~05·STOCK-SET-01~05를 기준으로 한다.

## 구현 범위와 책임

| 파일 | 책임 |
| --- | --- |
| [ProductStock](../../apps/commerce-api/src/main/java/com/loopers/domain/product/ProductStock.java) | 초기·최종 재고 비음수, 최종 수량 설정, 양수 수량 차감, 부족 거절, 실패 시 상태 보존 |
| [ProductStockException](../../apps/commerce-api/src/main/java/com/loopers/domain/product/ProductStockException.java) | 잘못된 초기·설정 재고, 차감 수량과 재고 부족의 실패 사유 표현 |
| [ProductStockTest](../../apps/commerce-api/src/test/java/com/loopers/domain/product/ProductStockTest.java) | 위 규칙과 정수 경계·연속 변경 검증, 설정 확장 후 매개변수 실행 포함 19개 사례 |

`ProductStock`은 향후 상품이 소유할 재고 수량 객체다. 이번에는 상품 엔티티·브랜드·가격·저장소·API를 추가하지 않았다. 첫 구현에서 `int`는 객체 내부 표현으로만 선택했다. 이후 설계 검토에서 사용자가 API·DB의 재고·주문 수량도 `Integer` 범위를 사용하도록 확인했다. 금액·포인트 자료형은 별도 검토 중이다. 예외에는 HTTP 상태·응답 코드를 넣지 않았다.

삭제된 상품의 차감을 거절하는 STOCK-06은 상품 생명주기와 연결할 때 구현한다. 이번 단위 테스트는 DB 동시성이나 주문 전체의 원자성을 증명하지 않는다.

## 실제 Red → Green → Refactor

각 실행은 테스트 클래스를 지정해 수행했다. 환경 실패를 업무 규칙의 Red로 기록하지 않았고, 기대값을 바꿔 통과시키지 않았다.

| 순서·대상 | 실제 확인한 결과 | 구현·정리와 재검증 |
| --- | --- | --- |
| 1 / STOCK-01 | 빈 차감 메서드에서 기대 재고 3, 실제 5로 실패. 1개 중 1개 실패 | 차감 연산 추가 후 1개 통과 |
| 2 / STOCK-02 | 재고 5에 6개 차감 시 예외가 없고 재고 -1. 2개 중 1개 실패 | 차감 전 부족 검사 추가 후 2개 통과 |
| 3 / STOCK-03 | 재고 5 전량 차감은 기존 구현으로 통과 | 구현 변경 없이 총 3개 통과 |
| 4 / STOCK-04 | 재고 0에서 1개 차감 거절·0 유지가 기존 구현으로 통과 | 구현 변경 없이 총 4개 통과 |
| 5 / STOCK-05 | 0·-1·Integer.MIN_VALUE 차감이 모두 거절되지 않음. -1은 재고 6, 최솟값은 -2147483643으로 변경됨. 7개 중 3개 실패 | 계산 전 양수 검사 추가 후 7개 통과 |
| 6 / 초기 상태 | -1·Integer.MIN_VALUE 재고 객체가 생성됨. 9개 중 2개 실패 | 생성자에서 음수 거절 후 9개 통과 |
| 7 / 정수 경계 | Integer.MAX_VALUE 재고의 전량 차감이 기존 구현으로 통과 | 구현 변경 없이 총 10개 통과 |
| 8 / 연속 호출 | 5 → 2개 차감 → 4개 차감 거절·3 유지 → 3개 차감 → 0이 기존 구현으로 통과 | 구현 변경 없이 총 11개 통과 |
| 9 / Refactor | 모든 테스트가 통과하는 상태에서 정리 | 차감 인자 이름 명확화, 재고 객체 상속 제한, 예외 사유·상태 보존 assertion 중복 추출 후 동일한 11개 통과 |

초기 사이클의 최소 구현은 별도 추상화가 필요하지 않아 유지했다. 반복된 실패 검증이 생긴 뒤 테스트 보조 메서드로 정리했으며, 각 사례의 실패 사유와 기대 재고는 그대로 유지했다. 경계 테스트가 처음부터 통과한 경우 인위적으로 실패를 만들지 않았다.

## 검사 결과

```shell
./gradlew :apps:commerce-api:test --tests 'com.loopers.domain.product.ProductStockTest' --console=plain -q
./gradlew :apps:commerce-api:check --console=plain
```

- 리팩터링 후 재고 단위 테스트: 11개 통과.
- 첫 전체 검사: 27개 중 17개 통과, 기존 통합·E2E·컨텍스트 테스트 10개는 Docker 연결 실패로 실패했다. 재고 테스트와 ArchUnit은 통과했다.
- Checkstyle main/test: 통과.
- Docker Desktop 실행 후 전체 검사 재실행: ArchUnit을 포함한 27개 모두 통과했으며 실패·오류·건너뜀은 0개다. 첫 환경 실패는 도메인 규칙의 Red와 구분한다.

## 계층·DIP 문서 보완 후 재검증

[구조와 코드 의존](commerce-erd-draft.md#구조와-코드-의존)에 패키지·계층·모듈, DIP와 DTO 경계, JPA 모델 분리 비용을 보완한 뒤 기존 재고 구현을 다시 확인했다.

- `ProductStock`은 자신의 수량과 차감 규칙을, `ProductStockException`은 업무 실패 사유를 담당한다. HTTP·JPA·다른 계층 타입에 의존하지 않아 기존 구현을 유지했다.
- 현재 규칙에는 외부 저장 기술을 호출하는 경계가 없으므로 새 저장소 인터페이스나 fake를 추가하지 않았다. 저장소가 필요한 유스케이스를 구현할 때 해당 경계를 정의한다.
- 재고 테스트 11개와 ArchUnit 1개를 다시 실행해 모두 통과했다. Checkstyle main/test는 소스 변경이 없어 `UP-TO-DATE`로 기존 통과 결과를 재사용했다. 이번에는 전체 통합 테스트를 다시 실행하지 않았다.
- 이 작업은 설계 설명 보완에 따른 재검증이며 새 Red → Green 사이클이 아니다. STOCK-06과 상품·API·DB 연결은 여전히 후속 범위다.

```shell
./gradlew :apps:commerce-api:test --tests 'com.loopers.domain.product.ProductStockTest' --tests 'com.loopers.architecture.ArchitectureTest' :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --console=plain
```

## 정책 변경의 후속 영향 확인

2026-09-18 정책 확정·보류가 다른 문서와 현재 구현에 미치는 영향을 확인했다.

- 재고의 `int` 표현은 확정한 Integer 범위와 일치한다. 기존 예제의 ID도 Long을 사용한다. 현재 코드·테스트·빌드 의존·ArchUnit 규칙에 수정이 필요한 충돌은 없었다.
- TDD 계획에 좋아요 관계의 물리 삭제·재등록, 논리 삭제 행 보존·재삭제, 충전·주문 생성의 반복 성공, HTTP 숫자 범위와 관리자 권한 우선의 검증을 보완했다. 이는 아직 구현하지 않은 API의 테스트 계획이며 실행 완료로 기록하지 않는다.
- 금액·포인트의 자료형은 미정으로 유지하고 경계 테스트의 상한을 자료형 결정 후 고정하도록 정리했다. 행 잠금 뒤 상태 검증·저장 원칙도 대표 흐름에 연결했다.
- `./gradlew :apps:commerce-api:check --console=plain` 실행 성공. 재고 11개·ArchUnit 1개를 포함한 전체 27개 테스트가 통과했고 실패·오류·건너뜀은 0개다. Checkstyle main/test는 `UP-TO-DATE`로 기존 통과 결과를 재사용했다.
- 작업 지침과 week2 문서의 내부 링크·앵커 56개, 코드 블록, API 계약 25개·API 테스트 사례 24개·재고 사례 6개의 유지와 `git diff --check`를 확인했다. 새 Red → Green 사이클이나 커머스 API 구현은 수행하지 않았다.

## 최종 수량 설정의 Red → Green → Refactor

2026-09-18 사용자 승인으로 A11·API-19의 최종 수량 설정 중 도메인 수량 규칙을 먼저 구현했다. `ProductStock.changeQuantityTo(int)`는 0 이상인 입력을 현재 재고로 설정한다. 음수는 기존 `INVALID_STOCK_QUANTITY` 사유로 거절하며 예외 타입이나 HTTP 계약을 변경하지 않았다.

| 순서·대상 | 실제 확인한 결과 | 구현·재검증 |
| --- | --- | --- |
| 1 / STOCK-SET-01 | 테스트와 빈 메서드 골격에서 기대 재고 3, 실제 10으로 실패. 기존 사례 포함 12개 중 1개 실패 | 최종 수량 대입 후 12개 통과 |
| 2 / STOCK-SET-02 | 재고 10을 0으로 설정하는 사례가 기존 구현으로 통과 | 구현 변경 없이 13개 통과 |
| 3 / STOCK-SET-03 | -1·Integer.MIN_VALUE 설정에서 예외가 없고 기존 재고 10이 각각 음수로 변경됨. 15개 중 2개 실패 | 변경 전 비음수 검사 추가 후 15개 통과 |
| 4 / STOCK-SET-04·05 | 같은 수량 10·증가 20·Integer.MAX_VALUE 설정, 설정과 차감의 연속 호출이 기존 구현으로 통과 | 구현 변경 없이 19개 통과 |
| 5 / Refactor | 생성자와 설정 메서드의 비음수 검증 중복 제거 | private 검증 함수로 추출. 전체 검사에서 재고 19개를 포함한 35개 통과 |

SET-05는 재고 10 → 최종 3 설정 → 2 차감하여 1 → 최종 8 설정 → 8 차감하여 0을 검증한다. 기존 차감 테스트 11개는 그대로 유지했고, 처음부터 통과한 추가 사례에 인위적인 실패를 만들지 않았다.

변경 diff는 도메인 메서드·공통 비음수 검사와 설정 테스트 8개 실행 사례 추가, 관련 계획·구현 상태 기록으로 한정된다. 기존 `domain/product` 패키지와 Layer-first 구조를 유지하며 HTTP·DB·다른 계층 의존은 추가하지 않았다. 상품 삭제 검증·관리자 권한·API·DB 잠금은 후속 범위다.

```shell
./gradlew :apps:commerce-api:test --tests 'com.loopers.domain.product.ProductStockTest' --console=plain -q
./gradlew :apps:commerce-api:check --console=plain
```

리팩터링 후 전체 35개 테스트(재고 19개·ArchUnit 1개 포함), Checkstyle main/test가 통과했다. 실패·오류·건너뜀은 0개다.
