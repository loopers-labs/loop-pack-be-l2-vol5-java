---
name: test-scenario-write
description: 도메인 규칙을 테스트 코드로 옮길 때 사용한다. docs/week2/domain-rules.yaml 의 규칙마다 경계값 분석·동등 클래스 분할·의사결정표·상태 전이·오류 추측 중 알맞은 기법을 매핑하고, 그 기법이 요구하는 값을 빠짐없이 써서 테스트를 작성한다. TDD 의 Red 단계에서 StockTest·OrderTest 같은 테스트를 쓸 때 쓴다.
---

# test-scenario-write

## 할 일

1. `docs/week2/domain-rules.yaml`에서 검증할 규칙(`INV-<개념>-<번호>`)을 고른다. 검증할 규칙에 ID가 없으면 만들어서 `domain-rules.yaml`에 넣는다. 규칙이 생기기를 기다리지 않는다.
2. 규칙 하나를 골라 아래 기법을 알맞게 매핑하고, **그 기법이 요구하는 값을 빠짐없이** 써서 테스트 코드를 작성한다. 테스트 파일은 `src/test/java`에서 대상과 같은 패키지에 `<대상>Test.java`로 둔다.
3. 테스트를 작성한 뒤 [Red 검증](./references/red-validation.md)을 수행한다. 검증을 통과하기 전에는 Green 구현을 시작하지 않는다.

규칙으로 환원되지 않는 것(조회 계약, 접근 경계, 응답 계약)은 통합·HTTP 테스트가 맡고 `docs/week2/requirements.md`의 요구사항 ID를 그대로 쓴다.

## 테스트 기법

3열은 권장이 아니라 **채워야 하는 자리**다. 한 자리라도 비면 그 기법을 적용했다고 말하지 않는다.

| 기법 | 이런 규칙에 | 빠짐없이 써야 하는 값 |
| --- | --- | --- |
| 경계값 분석 | 범위가 있다 | 최소 바로 아래, 최소값, 최대값, 최대 바로 위 (명목값은 선택) |
| 동등 클래스 분할 | 허용되는 입력과 거절되는 입력이 나뉜다 | 유효 클래스와 **무효 클래스 전부**에서 대표값 하나씩 |
| 의사결정표 | 결과가 **조건 둘 이상**의 조합으로 정해진다 | 조건의 모든 조합 |
| 상태 전이 | 상태에 따라 같은 요청의 결과가 다르다 | 허용되는 전이와 막히는 전이 |
| 오류 추측 | 경험상 잘 깨지는 곳이 있다 | 필수값 비움, 잘못된 형식, 예상치 못한 행동 |

### 범위는 양쪽 끝이 있다

`"~ 이하다"`, `"~ 이상이다"` 같은 규칙 문장은 한쪽 끝만 말한다. **문장이 말하지 않은 반대쪽 끝도 범위의 경계다.**

```
INV-POINT-20  결제액은 현재 잔액 이하다.
        ↓ 문장이 말한 것          ↓ 문장이 말하지 않은 것
범위     … 잔액, 잔액+1            잔액-1 아래 어딘가에 있는 하한, 그 바로 아래
```

하한 자리를 채우려면 "가장 작은 유효 결제액은 얼마인가"를 답해야 한다. 규칙에 근거가 없으면 **테스트를 쓰지 말고 질문한다**(Red 검증 3절).

### 조건이 하나면 의사결정표가 아니다

조건 하나에 값이 둘인 것은 동등 클래스 분할이다. 의사결정표는 조건이 둘 이상일 때만 쓴다.

```
소유자인가                        → 동등 클래스 분할
존재하는가 × 삭제되었는가          → 의사결정표
역할 × CSRF 유효성                → 의사결정표
```

## 테스트 코드 모양

- 규칙 하나는 `@Nested` 하나다. `@DisplayName`에 규칙 ID와 `domain-rules.yaml`의 규칙 문장을 **글자 그대로** 적는다. 문장이 어긋나면 `DomainRulesTest`가 아니라 사람이 읽을 때 드러난다.
- 테스트 하나는 시나리오 하나다. `@DisplayName` 앞에 기법 이름을 적는다.
- 거절은 **오류 코드와 변경 대상이 전부 그대로인지** 함께 확인한다. 바꾸려던 객체가 없는 거절(생성자, 조회만 하는 검사)은 지킬 상태가 없으므로 오류 코드만 확인한다.
- 도메인 테스트는 HTTP 상태를 확인하지 않는다.

```java
class StockTest {

    @DisplayName("[INV-STOCK-15] 차감 수량은 현재 재고 이하다.")
    @Nested
    class DecreaseWithinStock {

        // 최소값, 최대값
        @DisplayName("[경계값 분석] 재고 5에서 1, 5를 차감하면 4, 0이 남는다.")
        @ParameterizedTest
        @CsvSource({"1, 4", "5, 0"})
        void decreases_whenAmountIsWithinStock(int amount, int expected) {
            // arrange
            Stock stock = new Stock(5);

            // act
            Stock result = stock.decrease(amount);

            // assert
            assertThat(result.quantity()).isEqualTo(expected);
        }

        // 최대 바로 위
        @DisplayName("[경계값 분석] 재고 5에서 6을 차감하면 재고 부족으로 거절하고, 재고는 5 그대로다.")
        @Test
        void throwsInsufficientStock_whenAmountExceedsStock() {
            // arrange
            Stock stock = new Stock(5);

            // act
            CoreException result = assertThrows(CoreException.class, () -> stock.decrease(6));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK),
                () -> assertThat(stock.quantity()).isEqualTo(5)
            );
        }

        // 최소 바로 아래 — 근거는 ADR-003
        @DisplayName("[경계값 분석] 재고 5에서 0을 차감하면 내부 오류로 거절하고, 재고는 5 그대로다.")
        @Test
        void throwsInternalError_whenAmountIsNotPositive() {
            // arrange
            Stock stock = new Stock(5);

            // act
            CoreException result = assertThrows(CoreException.class, () -> stock.decrease(0));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR),
                () -> assertThat(stock.quantity()).isEqualTo(5)
            );
        }
    }
}
```

네 시나리오가 **최소 바로 아래·최소·최대·최대 바로 위**에 하나씩 대응한다. 주석이 그 대응을 보여 준다.

## 단계 경계

이 스킬은 **Red 전용**이다.

- 규칙에 근거한 테스트를 작성하고, 테스트가 의도한 미구현 동작 때문에 실패하는지 확인한다.
- 테스트를 통과시키기 위한 production 규칙은 구현하지 않는다.
- Red 검증이 끝나면 테스트 파일, 규칙 ID, 실행 명령, 통과·실패 수와 실패 원인, **비워 둔 경계 자리와 그 질문**만 남기고 종료한다.
- Green 은 이전 대화 문맥을 공유하지 않는 별도 에이전트에서 `test-green-implement` 스킬로 수행한다.
