package com.loopers.interfaces.api.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.loopers.application.coupon.queue.CouponRequestQueue;
import com.loopers.application.coupon.queue.CouponRequestQueue.SubmitOutcome;
import com.loopers.application.coupon.queue.CouponRequestQueue.SubmitResult;
import com.loopers.application.coupon.queue.CouponRequestService;
import com.loopers.application.coupon.queue.CouponQueueWorkerState;
import com.loopers.application.user.IdentifyUser;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.interfaces.api.ApiControllerAdvice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.time.ZonedDateTime;

@ExtendWith(MockitoExtension.class)
class CouponRequestControllerTest {
    @Mock private IdentifyUser users;
    @Mock private CouponRequestQueue queue;
    @Mock private CouponRepository coupons;
    private MockMvc mvc;
    private CouponQueueWorkerState state;

    @BeforeEach
    void setUp() {
        state = new CouponQueueWorkerState(true);
        mvc = MockMvcBuilders.standaloneSetup(new CouponRequestController(new CouponRequestService(users, queue, coupons, state)))
                .setControllerAdvice(new ApiControllerAdvice())
                .build();
    }

    @Test
    void 이미_쿠폰이_있으면_Redis_없이_기존_ID를_반환한다() throws Exception {
        state.pause();
        when(users.require(11L)).thenReturn(11L);
        when(coupons.findByCampaignAndUser(7L, 11L))
                .thenReturn(Optional.of(new Coupon(42, 7, 11, "할인권", ZonedDateTime.now())));

        mvc.perform(post("/api/v1/coupon-campaigns/7/issue-requests").header("X-USER-ID", "11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.couponId").value(42));

        verifyNoInteractions(queue);
    }

    @Test
    void 접수되면_번호표_대신_쿠폰_목록_확인을_안내한다() throws Exception {
        when(users.require(11L)).thenReturn(11L);
        when(queue.submit(7L, 11L)).thenReturn(new SubmitResult(SubmitOutcome.ACCEPTED, "ticket-1"));

        mvc.perform(post("/api/v1/coupon-campaigns/7/issue-requests").header("X-USER-ID", "11"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.requestId").doesNotExist())
                .andExpect(jsonPath("$.data.message").value("쿠폰 신청이 접수되었습니다. 잠시 후 쿠폰 목록을 확인해 주세요."));
    }

    @Test
    void 대기열이_가득_차면_접수를_거절한다() throws Exception {
        when(users.require(11L)).thenReturn(11L);
        when(queue.submit(7L, 11L)).thenReturn(new SubmitResult(SubmitOutcome.FULL, null));

        mvc.perform(post("/api/v1/coupon-campaigns/7/issue-requests").header("X-USER-ID", "11"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.meta.errorCode").value("COUPON_ADMISSION_FULL"));
    }

    @Test
    void 작업자가_중단된_서버에서_신규_접수를_거절하면_서비스_불가를_반환한다() throws Exception {
        when(users.require(11L)).thenReturn(11L);
        when(queue.submit(7L, 11L)).thenReturn(new SubmitResult(SubmitOutcome.PAUSED, null));

        mvc.perform(post("/api/v1/coupon-campaigns/7/issue-requests").header("X-USER-ID", "11"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.meta.errorCode").value("COUPON_ADMISSION_UNAVAILABLE"));
    }

    @Test
    void 기존_신청을_다시_요청하면_접수_안내를_반환한다() throws Exception {
        when(users.require(11L)).thenReturn(11L);
        when(queue.submit(7L, 11L)).thenReturn(new SubmitResult(SubmitOutcome.EXISTING, "ticket-1"));

        mvc.perform(post("/api/v1/coupon-campaigns/7/issue-requests").header("X-USER-ID", "11"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.requestId").doesNotExist())
                .andExpect(jsonPath("$.data.message").value("쿠폰 신청이 접수되었습니다. 잠시 후 쿠폰 목록을 확인해 주세요."));
    }

    @Test
    void 사용자_확인시_DB_연결_실패하면_접수_불가를_반환한다() throws Exception {
        when(users.require(11L)).thenThrow(new DataAccessResourceFailureException("database unavailable"));

        mvc.perform(post("/api/v1/coupon-campaigns/7/issue-requests").header("X-USER-ID", "11"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.meta.errorCode").value("COUPON_ADMISSION_UNAVAILABLE"));
        assertThat(state.processingAllowed()).isFalse();
    }

    @Test
    void 기존_쿠폰_확인시_DB_연결_실패하면_대기열에_접수하지_않는다() throws Exception {
        when(users.require(11L)).thenReturn(11L);
        when(coupons.findByCampaignAndUser(7L, 11L))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        mvc.perform(post("/api/v1/coupon-campaigns/7/issue-requests").header("X-USER-ID", "11"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.meta.errorCode").value("COUPON_ADMISSION_UNAVAILABLE"));

        verifyNoInteractions(queue);
        assertThat(state.processingAllowed()).isFalse();
    }

}
