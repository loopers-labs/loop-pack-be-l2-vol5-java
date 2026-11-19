package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.admission.AcquireOutcome;
import com.loopers.application.coupon.admission.CouponIssueAdmission;
import com.loopers.application.coupon.admission.ReserveOutcome;
import com.loopers.application.coupon.admission.ReserveResult;
import com.loopers.support.concurrent.ConcurrentRequests;
import com.loopers.support.concurrent.ConcurrentRequests.Results;
import com.loopers.support.fixture.CouponAdmissionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 당첨·자리·결과 기록 규칙이 Lua 안에 있으므로 실제 Redis 에서 스크립트 결과와 남은 상태를 확인한다.
 * 수용량 C 는 2 로 두어 자리가 가득 찬 상태를 작게 만든다. 이 테스트가 만든 캠페인 키만 지운다.
 */
@SpringBootTest(properties = "coupon.issue.capacity=2")
class CouponIssueRedisAdmissionTest {

    private static final long CAMPAIGN = 1L;
    private static final long USER = 7L;
    private static final String TOKEN = "token-mine";
    private static final String OTHER_TOKEN = "token-other";

    @Autowired
    private CouponIssueAdmission admission;

    @Autowired
    private CouponAdmissionState state;

    @AfterEach
    void tearDown() {
        state.clear(CAMPAIGN);
    }

    private static Consumer<CouponAdmissionState> openCampaign(int quota) {
        return s -> s.meta(CAMPAIGN, true, true, quota);
    }

    @DisplayName("당첨을 판정할 때,")
    @Nested
    class Reserve {

        static Stream<Arguments> states() {
            Consumer<CouponAdmissionState> open = openCampaign(3);
            return Stream.of(
                arguments(Named.of("수용이 닫혀 있으면 CLOSED",
                    (Consumer<CouponAdmissionState>) s -> s.meta(CAMPAIGN, false, true, 3)), ReserveOutcome.CLOSED, null),
                arguments(Named.of("종료된 캠페인이어도 이미 받은 사람은 ISSUED 와 couponId",
                    (Consumer<CouponAdmissionState>) s -> {
                        s.meta(CAMPAIGN, true, false, 3);
                        s.winner(CAMPAIGN, USER, "1024");
                    }), ReserveOutcome.ISSUED, 1024L),
                arguments(Named.of("202 로 약속한 당첨이면 TICKETED",
                    open.andThen(s -> s.winner(CAMPAIGN, USER, "TICKETED"))), ReserveOutcome.TICKETED, null),
                arguments(Named.of("처리 중인 당첨이면 PENDING",
                    open.andThen(s -> s.winner(CAMPAIGN, USER, "PENDING"))), ReserveOutcome.PENDING, null),
                arguments(Named.of("종료된 캠페인의 새 사용자는 INACTIVE",
                    (Consumer<CouponAdmissionState>) s -> s.meta(CAMPAIGN, true, false, 3)), ReserveOutcome.INACTIVE, null),
                arguments(Named.of("당첨자가 한도만큼 있으면 SOLD_OUT",
                    open.andThen(s -> {
                        s.winner(CAMPAIGN, 1L, "PENDING");
                        s.winner(CAMPAIGN, 2L, "TICKETED");
                        s.winner(CAMPAIGN, 3L, "1001");
                    })), ReserveOutcome.SOLD_OUT, null),
                arguments(Named.of("새 당첨이고 자리가 있으면 WON_RUNNING", open), ReserveOutcome.WON_RUNNING, null),
                arguments(Named.of("새 당첨이고 자리가 가득 차면 WON_WAITING",
                    open.andThen(s -> s.running(CAMPAIGN, "token-a", "token-b"))), ReserveOutcome.WON_WAITING, null)
            );
        }

        @DisplayName("winners·meta·running 상태에 따라 결과가 정해진다.")
        @ParameterizedTest(name = "{0}")
        @MethodSource("states")
        void reserve_decidesByState(Consumer<CouponAdmissionState> given, ReserveOutcome expected, Long expectedCouponId) {
            // arrange
            given.accept(state);

            // act
            ReserveResult result = admission.reserve(CAMPAIGN, USER, TOKEN);

            // assert
            assertThat(result.outcome()).isEqualTo(expected);
            assertThat(result.couponId()).isEqualTo(expectedCouponId);
        }

        @DisplayName("새 당첨은 PENDING 으로 기록되고, 자리가 있을 때만 자기 token 이 running 에 들어간다.")
        @Test
        void reserve_recordsPendingAndHoldsSlotOnlyWhenRunning() {
            // arrange
            state.meta(CAMPAIGN, true, true, 3);

            // act
            admission.reserve(CAMPAIGN, USER, TOKEN);
            state.running(CAMPAIGN, OTHER_TOKEN);
            admission.reserve(CAMPAIGN, 8L, "token-waiting");

            // assert
            assertThat(state.winnerOf(CAMPAIGN, USER)).isEqualTo("PENDING");
            assertThat(state.winnerOf(CAMPAIGN, 8L)).isEqualTo("PENDING");
            assertThat(state.runningTokens(CAMPAIGN)).containsExactlyInAnyOrder(TOKEN, OTHER_TOKEN);
        }

        @DisplayName("한도 5에 20명이 동시에 요청하면 당첨 5·품절 15이고 당첨자 명단은 5명이다.")
        @Test
        void reserve_grantsOnlyQuotaWinners_whenUsersRequestConcurrently() {
            // arrange
            state.meta(CAMPAIGN, true, true, 5);
            Queue<ReserveOutcome> outcomes = new ConcurrentLinkedQueue<>();
            List<Runnable> requests = new ArrayList<>();
            for (long userId = 1; userId <= 20; userId++) {
                long requester = userId;
                requests.add(() -> outcomes.add(admission.reserve(CAMPAIGN, requester, "token-" + requester).outcome()));
            }

            // act
            Results results = ConcurrentRequests.run(requests);

            // assert
            assertThat(results.technicalErrors()).isZero();
            assertThat(outcomes)
                .filteredOn(o -> o == ReserveOutcome.WON_RUNNING || o == ReserveOutcome.WON_WAITING).hasSize(5);
            assertThat(outcomes).filteredOn(o -> o == ReserveOutcome.SOLD_OUT).hasSize(15);
            assertThat(state.winnerCount(CAMPAIGN)).isEqualTo(5);
        }
    }

    @DisplayName("당첨자가 자리를 다시 요청할 때,")
    @Nested
    class Acquire {

        static Stream<Arguments> states() {
            Consumer<CouponAdmissionState> open = openCampaign(3);
            return Stream.of(
                arguments(Named.of("수용이 닫혀 있으면 CLOSED",
                    (Consumer<CouponAdmissionState>) s -> {
                        s.meta(CAMPAIGN, false, true, 3);
                        s.winner(CAMPAIGN, USER, "PENDING");
                    }), AcquireOutcome.CLOSED, ExpectedRunning.EMPTY),
                arguments(Named.of("이미 couponId 가 기록됐으면 NOT_PENDING",
                    open.andThen(s -> s.winner(CAMPAIGN, USER, "1024"))), AcquireOutcome.NOT_PENDING, ExpectedRunning.EMPTY),
                arguments(Named.of("자리가 가득 차면 FULL",
                    open.andThen(s -> {
                        s.winner(CAMPAIGN, USER, "PENDING");
                        s.running(CAMPAIGN, "token-a", "token-b");
                    })), AcquireOutcome.FULL, ExpectedRunning.OTHERS),
                arguments(Named.of("약속한 당첨(TICKETED)도 자리가 있으면 ACQUIRED",
                    open.andThen(s -> {
                        s.winner(CAMPAIGN, USER, "TICKETED");
                        s.running(CAMPAIGN, OTHER_TOKEN);
                    })), AcquireOutcome.ACQUIRED, ExpectedRunning.MINE_AND_OTHER),
                arguments(Named.of("같은 실행의 token 이 이미 있으면 자리를 더 쓰지 않고 ACQUIRED",
                    open.andThen(s -> {
                        s.winner(CAMPAIGN, USER, "PENDING");
                        s.running(CAMPAIGN, TOKEN, OTHER_TOKEN);
                    })), AcquireOutcome.ACQUIRED, ExpectedRunning.MINE_AND_OTHER)
            );
        }

        @DisplayName("당첨 상태와 남은 자리에 따라 결과가 정해지고, 자리는 C 를 넘지 않는다.")
        @ParameterizedTest(name = "{0}")
        @MethodSource("states")
        void acquire_decidesByState(Consumer<CouponAdmissionState> given, AcquireOutcome expected, ExpectedRunning expectedRunning) {
            // arrange
            given.accept(state);

            // act
            AcquireOutcome outcome = admission.acquire(CAMPAIGN, USER, TOKEN);

            // assert
            assertThat(outcome).isEqualTo(expected);
            assertThat(state.runningTokens(CAMPAIGN)).containsExactlyInAnyOrderElementsOf(expectedRunning.tokens);
        }
    }

    /** acquire 뒤 running 에 남아야 할 token. */
    enum ExpectedRunning {
        EMPTY(List.of()),
        OTHERS(List.of("token-a", "token-b")),
        MINE_AND_OTHER(List.of(TOKEN, OTHER_TOKEN));

        private final List<String> tokens;

        ExpectedRunning(List<String> tokens) {
            this.tokens = tokens;
        }
    }

    @DisplayName("당첨자의 값을 바꿀 때,")
    @Nested
    class WinnerValue {

        static Stream<Arguments> changes() {
            return Stream.of(
                arguments(Named.of("ticket: PENDING 은 TICKETED 가 된다", "ticket"), "PENDING", null, "TICKETED"),
                arguments(Named.of("ticket: 이미 발급된 값은 그대로다", "ticket"), "1024", null, "1024"),
                arguments(Named.of("finish: PENDING 은 couponId 가 된다", "finish"), "PENDING", 2048L, "2048"),
                arguments(Named.of("finish: TICKETED 는 couponId 가 된다", "finish"), "TICKETED", 2048L, "2048"),
                arguments(Named.of("finish: 이미 발급된 값은 그대로다", "finish"), "1024", 2048L, "1024"),
                arguments(Named.of("finish: 저장에 실패해 couponId 가 없으면 PENDING 그대로다", "finish"), "PENDING", null, "PENDING")
            );
        }

        @DisplayName("발급 전 값(PENDING·TICKETED)만 바뀌고 발급된 couponId 는 덮어쓰지 않는다.")
        @ParameterizedTest(name = "{0}")
        @MethodSource("changes")
        void winnerValue_changesOnlyBeforeIssued(String operation, String before, Long couponId, String after) {
            // arrange
            state.meta(CAMPAIGN, true, true, 3);
            state.winner(CAMPAIGN, USER, before);

            // act
            if (operation.equals("ticket")) {
                admission.markTicketed(CAMPAIGN, USER);
            } else {
                admission.finish(CAMPAIGN, USER, TOKEN, couponId);
            }

            // assert
            assertThat(state.winnerOf(CAMPAIGN, USER)).isEqualTo(after);
        }
    }

    @DisplayName("자리를 반환할 때,")
    @Nested
    class Finish {
        @DisplayName("같은 token 을 두 번 반환하거나 모르는 token 을 반환해도 다른 실행의 token 은 남는다.")
        @Test
        void finish_removesOnlyOwnToken_whenCalledTwiceOrWithUnknownToken() {
            // arrange
            state.meta(CAMPAIGN, true, true, 3);
            state.winner(CAMPAIGN, USER, "PENDING");
            state.running(CAMPAIGN, TOKEN, OTHER_TOKEN);

            // act
            admission.finish(CAMPAIGN, USER, TOKEN, null);
            admission.finish(CAMPAIGN, USER, TOKEN, null);
            admission.finish(CAMPAIGN, USER, "token-unknown", null);

            // assert
            assertThat(state.runningTokens(CAMPAIGN)).containsExactly(OTHER_TOKEN);
        }

        @DisplayName("점유한 자리에는 만료 시간이 없어, 시간이 지나도 자동으로 반환되지 않는다.")
        @Test
        void runningToken_hasNoExpiry() {
            // arrange
            state.meta(CAMPAIGN, true, true, 3);

            // act
            admission.reserve(CAMPAIGN, USER, TOKEN);

            // assert
            assertThat(state.runningTokens(CAMPAIGN)).containsExactly(TOKEN);
            assertThat(state.runningTtlSeconds(CAMPAIGN)).isEqualTo(-1);
        }
    }
}
