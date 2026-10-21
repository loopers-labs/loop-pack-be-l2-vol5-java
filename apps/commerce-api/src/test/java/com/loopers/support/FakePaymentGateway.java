package com.loopers.support;

import com.loopers.application.order.PaymentGateway;
import com.loopers.application.order.PaymentGatewayResult;
import com.loopers.application.order.PaymentGatewayResult.Outcome;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 테스트용 PaymentGateway. @Primary 라 모든 @SpringBootTest 에서 PgClient 대신 주입된다 (HTTP 없음).
 * 기본: 결제 요청은 요청 금액 그대로 SUCCESS (거래 키 "tx-{orderId}"), 거래 조회는 UNKNOWN.
 * 응답을 바꾸는 테스트는 willRespond·willFind 로 정하고 @AfterEach 에서 reset() 한다.
 */
@Primary
@Component
public class FakePaymentGateway implements PaymentGateway {
    private static final Function<Request, PaymentGatewayResult> DEFAULT_REQUEST =
        request -> success(transactionKeyOf(request.orderId()), request.amount());
    private static final Function<Long, PaymentGatewayResult> DEFAULT_FIND = orderId -> PaymentGatewayResult.unknown();

    private volatile Function<Request, PaymentGatewayResult> onRequest = DEFAULT_REQUEST;
    private volatile Function<Long, PaymentGatewayResult> onFind = DEFAULT_FIND;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<Long> finds = new CopyOnWriteArrayList<>();

    /** 받은 결제 요청 한 건. */
    public record Request(Long userId, Long orderId, String cardType, String cardNo, Long amount) {
    }

    @Override
    public PaymentGatewayResult request(Long userId, Long orderId, String cardType, String cardNo, Long amount) {
        Request request = new Request(userId, orderId, cardType, cardNo, amount);
        requests.add(request);
        return onRequest.apply(request);
    }

    @Override
    public PaymentGatewayResult find(Long userId, Long orderId) {
        finds.add(orderId);
        return onFind.apply(orderId);
    }

    /** 다음 결제 요청부터 이 결과를 돌려준다. */
    public void willRespond(PaymentGatewayResult result) {
        this.onRequest = request -> result;
    }

    /** 다음 결제 요청부터 요청을 보고 결과를 정한다 (요청 도중 다른 일을 끼워 넣을 때). */
    public void willRespond(Function<Request, PaymentGatewayResult> answer) {
        this.onRequest = answer;
    }

    /** 다음 거래 조회부터 이 결과를 돌려준다. */
    public void willFind(PaymentGatewayResult result) {
        this.onFind = orderId -> result;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /** 거래 조회를 받은 주문 ID, 받은 순서대로. */
    public List<Long> finds() {
        return List.copyOf(finds);
    }

    public void reset() {
        onRequest = DEFAULT_REQUEST;
        onFind = DEFAULT_FIND;
        requests.clear();
        finds.clear();
    }

    public static String transactionKeyOf(Long orderId) {
        return "tx-" + orderId;
    }

    public static PaymentGatewayResult success(String transactionKey, Long amount) {
        return new PaymentGatewayResult(Outcome.SUCCESS, transactionKey, amount);
    }

    public static PaymentGatewayResult pending(String transactionKey) {
        return new PaymentGatewayResult(Outcome.PENDING, transactionKey, null);
    }

    public static PaymentGatewayResult failed(String transactionKey) {
        return new PaymentGatewayResult(Outcome.FAILED, transactionKey, null);
    }
}
