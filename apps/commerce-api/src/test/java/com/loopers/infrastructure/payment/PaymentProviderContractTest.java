package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.domain.payment.PaymentCreateRequest;
import com.loopers.domain.payment.PaymentGatewayResult;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.infrastructure.payment.support.LocalTestPg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Real loopback HTTP contract only: no order, reservation or DB effects are claimed here. */
@Timeout(10)
class PaymentProviderContractTest {
    private static final String ORDER_ID = "LP-ORD-0000000042";
    private static final String CARD = "0000-0000-0000-0000";
    private static final long AMOUNT = 7000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LocalTestPg pg;
    private HttpTestPgClient client;

    @BeforeEach
    void setUp() throws Exception {
        pg = new LocalTestPg();
        client = new HttpTestPgClient(pg.baseUri(), pg.callbackUri(), MAPPER, PgHttpTimeouts.defaults());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        if (pg != null) {
            pg.close();
        }
    }

    @Test
    void sendsTheFixedLocalContractAndDoesNotTreatAcceptedAsSuccess() {
        var result = client.create(request());

        assertThat(result.kind()).isEqualTo(PaymentGatewayResult.Kind.OBSERVED);
        assertThat(result.failure()).isEqualTo(PaymentGatewayResult.Failure.NONE);
        assertThat(result.transactions()).singleElement().satisfies(transaction -> {
            assertThat(transaction.providerAccount()).isEqualTo("135135");
            assertThat(transaction.providerOrderId()).isEqualTo(ORDER_ID);
            assertThat(transaction.amount()).isEqualTo(AMOUNT);
            assertThat(transaction.transactionKey()).isNotBlank();
            assertThat(transaction.status()).isEqualTo(PaymentTransaction.Status.PENDING);
        });
        assertThat(pg.requests()).singleElement().satisfies(captured -> {
            assertThat(captured.method()).isEqualTo("POST");
            assertThat(captured.rawPath()).isEqualTo("/api/v1/payments");
            assertThat(captured.rawQuery()).isNull();
            assertThat(captured.account()).isEqualTo("135135");
            assertThat(captured.fieldTypes()).isEqualTo(Map.of(
                "orderId", "STRING", "cardType", "STRING", "cardNo", "STRING",
                "amount", "NUMBER", "callbackUrl", "STRING"));
            assertThat(captured.orderId()).isEqualTo(ORDER_ID);
            assertThat(captured.amount()).isEqualTo(AMOUNT);
            assertThat(captured.cardType()).isEqualTo("TEST");
            assertThat(captured.cardMatchesExpected()).isTrue();
            assertThat(captured.callbackUrl()).isEqualTo(pg.callbackUri().toString());
        });
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.transactionCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(PaymentTransaction.Status.class)
    void looksUpTheSameTransactionAndMapsAllExternalStates(PaymentTransaction.Status status) {
        var created = client.create(request()).transactions().getFirst();
        pg.setStatus(created.transactionKey(), status.name());

        var byKey = client.findByTransactionKey(created.transactionKey());
        var byOrder = client.findByProviderOrderId(ORDER_ID);

        var expected = new PaymentTransaction("135135", ORDER_ID, created.transactionKey(), AMOUNT, status);
        assertThat(byKey.kind()).isEqualTo(PaymentGatewayResult.Kind.OBSERVED);
        assertThat(byOrder.kind()).isEqualTo(PaymentGatewayResult.Kind.OBSERVED);
        assertThat(byKey.transactions()).containsExactly(expected);
        assertThat(byOrder.transactions()).containsExactly(expected);
        assertThat(pg.requests().subList(1, 3)).allSatisfy(captured -> {
            assertThat(captured.method()).isEqualTo("GET");
            assertThat(captured.account()).isEqualTo("135135");
            assertThat(captured.fieldTypes()).isEmpty();
        });
        assertThat(pg.requests().get(1).rawPath()).isEqualTo("/api/v1/payments/" + created.transactionKey());
        assertThat(pg.requests().get(2).rawPath()).isEqualTo("/api/v1/payments");
        assertThat(pg.requests().get(2).rawQuery()).isEqualTo("orderId=" + ORDER_ID);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.lookupCalls()).isEqualTo(2);
    }

    @Test
    void preservesAbsentAndMultipleTransactionsWithoutInventingAPaymentResult() {
        var missingKey = client.findByTransactionKey("MISSING-TRANSACTION");
        var emptyOrder = client.findByProviderOrderId(ORDER_ID);

        assertThat(missingKey.kind()).isEqualTo(PaymentGatewayResult.Kind.NOT_FOUND);
        assertThat(emptyOrder.kind()).isEqualTo(PaymentGatewayResult.Kind.NOT_FOUND);
        assertThat(missingKey.transactions()).isEmpty();
        assertThat(emptyOrder.transactions()).isEmpty();

        var first = client.create(request()).transactions().getFirst();
        var second = client.create(request()).transactions().getFirst();
        var multiple = client.findByProviderOrderId(ORDER_ID);

        assertThat(multiple.kind()).isEqualTo(PaymentGatewayResult.Kind.CONFLICT);
        assertThat(multiple.transactions()).containsExactlyInAnyOrder(first, second);
        assertThat(first.transactionKey()).isNotEqualTo(second.transactionKey());
        assertThat(pg.createCalls()).isEqualTo(2);
        assertThat(pg.transactionCount()).isEqualTo(2);
    }

    @Test
    void acceptsOnlyTheExactPreCreateRejectionWithoutFabricatingATransactionKey() {
        pg.nextCreateMode(LocalTestPg.CreateMode.REJECT_BEFORE_CREATE);

        var result = client.create(request());
        var lookup = client.findByProviderOrderId(ORDER_ID);

        assertThat(result.kind()).isEqualTo(PaymentGatewayResult.Kind.REJECTED_NO_EFFECT);
        assertThat(result.failure()).isEqualTo(PaymentGatewayResult.Failure.NONE);
        assertThat(result.transactions()).isEmpty();
        assertThat(lookup.kind()).isEqualTo(PaymentGatewayResult.Kind.NOT_FOUND);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.transactionCount()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRejections")
    void incompleteRejectionProofIsUnknown(String description, String json) {
        pg.replyNext(422, json);

        var result = client.create(request());

        assertUnknown(result, PaymentGatewayResult.Failure.INVALID_RESPONSE);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.transactionCount()).isZero();
    }

    private static Stream<Arguments> invalidRejections() {
        ObjectNode baseline = MAPPER.createObjectNode()
            .put("code", "TEST_PG_REJECTED_BEFORE_CREATE").put("orderId", ORDER_ID)
            .put("amount", AMOUNT).put("transactionCreated", false);
        return Stream.of(
            Arguments.of("empty", ""),
            Arguments.of("malformed JSON", "{"),
            Arguments.of("null root", "null"),
            Arguments.of("array root", "[]"),
            Arguments.of("duplicate proof field", rejection().replace("\"transactionCreated\":false",
                "\"transactionCreated\":true,\"transactionCreated\":false")),
            Arguments.of("trailing root", rejection() + "{}"),
            Arguments.of("wrong code", baseline.deepCopy().put("code", "DECLINED").toString()),
            Arguments.of("missing code", without(baseline, "code")),
            Arguments.of("string false", baseline.deepCopy().put("transactionCreated", "false").toString()),
            Arguments.of("true", baseline.deepCopy().put("transactionCreated", true).toString()),
            Arguments.of("null created flag", baseline.deepCopy().putNull("transactionCreated").toString()),
            Arguments.of("missing created flag", without(baseline, "transactionCreated")),
            Arguments.of("different order", baseline.deepCopy().put("orderId", "LP-OTHER-ORDER").toString()),
            Arguments.of("numeric order", baseline.deepCopy().put("orderId", 42).toString()),
            Arguments.of("missing order", without(baseline, "orderId")),
            Arguments.of("different amount", baseline.deepCopy().put("amount", AMOUNT + 1).toString()),
            Arguments.of("string amount", baseline.deepCopy().put("amount", "7000").toString()),
            Arguments.of("decimal amount", baseline.deepCopy().put("amount", 7000.0).toString()),
            Arguments.of("missing amount", without(baseline, "amount")),
            Arguments.of("overflow amount", "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\""
                + ORDER_ID + "\",\"amount\":9223372036854775808,\"transactionCreated\":false}")
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 400, 401, 403, 404, 409, 429, 500, 503})
    void otherHttpStatusesCannotProveNoEffectEvenWithTheRejectionBody(int status) {
        pg.replyNext(status, rejection());

        var result = client.create(request());

        assertUnknown(result, PaymentGatewayResult.Failure.HTTP_STATUS);
        assertThat(pg.createCalls()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTransactions")
    void acceptedWithAnInvalidTransactionIsUnknown(String description, String json) {
        pg.replyNext(202, json);

        var result = client.create(request());

        assertUnknown(result, PaymentGatewayResult.Failure.INVALID_RESPONSE);
        assertThat(pg.createCalls()).isEqualTo(1);
    }

    private static Stream<Arguments> invalidTransactions() {
        ObjectNode baseline = transaction("TEST-TX-42", ORDER_ID, AMOUNT, "PENDING");
        return Stream.of(
            Arguments.of("empty", ""),
            Arguments.of("malformed", "{"),
            Arguments.of("null root", "null"),
            Arguments.of("array root", "[]"),
            Arguments.of("duplicate transaction key", baseline.toString().replace("\"TEST-TX-42\"",
                "\"TEST-TX-OTHER\",\"transactionKey\":\"TEST-TX-42\"")),
            Arguments.of("trailing root", baseline + "{}"),
            Arguments.of("missing transaction key", without(baseline, "transactionKey")),
            Arguments.of("blank transaction key", baseline.deepCopy().put("transactionKey", " ").toString()),
            Arguments.of("numeric transaction key", baseline.deepCopy().put("transactionKey", 42).toString()),
            Arguments.of("missing order", without(baseline, "orderId")),
            Arguments.of("numeric order", baseline.deepCopy().put("orderId", 42).toString()),
            Arguments.of("blank order", baseline.deepCopy().put("orderId", " ").toString()),
            Arguments.of("missing amount", without(baseline, "amount")),
            Arguments.of("string amount", baseline.deepCopy().put("amount", "7000").toString()),
            Arguments.of("decimal amount", baseline.deepCopy().put("amount", 7000.0).toString()),
            Arguments.of("negative amount", baseline.deepCopy().put("amount", -1).toString()),
            Arguments.of("missing status", without(baseline, "status")),
            Arguments.of("unknown status", baseline.deepCopy().put("status", "APPROVED").toString()),
            Arguments.of("lowercase status", baseline.deepCopy().put("status", "success").toString()),
            Arguments.of("numeric status", baseline.deepCopy().put("status", 1).toString()),
            Arguments.of("overflow amount", "{\"transactionKey\":\"TEST-TX-42\",\"orderId\":\""
                + ORDER_ID + "\",\"amount\":9223372036854775808,\"status\":\"SUCCESS\"}")
        );
    }

    @Test
    void aValidButDifferentOrderOrAmountIsAConflictNotAPaymentFailure() {
        var otherOrder = transaction("TEST-TX-42", "LP-OTHER-ORDER", AMOUNT, "SUCCESS");
        pg.replyNext(202, otherOrder.toString());
        var orderConflict = client.create(request());

        pg.replyNext(202, transaction("TEST-TX-42", ORDER_ID, AMOUNT + 1, "FAILED").toString());
        var amountConflict = client.create(request());

        assertThat(orderConflict.kind()).isEqualTo(PaymentGatewayResult.Kind.CONFLICT);
        assertThat(amountConflict.kind()).isEqualTo(PaymentGatewayResult.Kind.CONFLICT);
        assertThat(orderConflict.transactions()).singleElement()
            .extracting(PaymentTransaction::providerOrderId).isEqualTo("LP-OTHER-ORDER");
        assertThat(amountConflict.transactions()).singleElement()
            .extracting(PaymentTransaction::amount).isEqualTo(AMOUNT + 1);
    }

    @Test
    void lookupIdentityMismatchesAreConflicts() {
        pg.replyNext(200, transaction("DIFFERENT-KEY", ORDER_ID, AMOUNT, "SUCCESS").toString());
        var keyConflict = client.findByTransactionKey("EXPECTED-KEY");
        pg.replyNext(200, "[" + transaction("TEST-TX-42", "LP-OTHER-ORDER", AMOUNT, "SUCCESS") + "]");
        var orderConflict = client.findByProviderOrderId(ORDER_ID);

        assertThat(keyConflict.kind()).isEqualTo(PaymentGatewayResult.Kind.CONFLICT);
        assertThat(orderConflict.kind()).isEqualTo(PaymentGatewayResult.Kind.CONFLICT);
        assertThat(keyConflict.transactions()).hasSize(1);
        assertThat(orderConflict.transactions()).hasSize(1);
        assertThat(pg.createCalls()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[null]", "[{}]", "[", "true"})
    void invalidOrderLookupDoesNotBecomeNotFound(String json) {
        pg.replyNext(200, json);

        assertUnknown(client.findByProviderOrderId(ORDER_ID), PaymentGatewayResult.Failure.INVALID_RESPONSE);
        assertThat(pg.createCalls()).isZero();
    }

    @Test
    void oneMalformedListMemberInvalidatesTheWholeObservation() {
        pg.replyNext(200, "[" + transaction("TEST-TX-42", ORDER_ID, AMOUNT, "SUCCESS") + ",{}]");

        assertUnknown(client.findByProviderOrderId(ORDER_ID), PaymentGatewayResult.Failure.INVALID_RESPONSE);
    }

    @Test
    void oversizedResponseIsNotAcceptedAndDoesNotPreventTheNextLookup() {
        pg.replyNext(200, "[" + " ".repeat(65_536) + "]");

        var result = client.findByProviderOrderId(ORDER_ID);

        assertUnknown(result, PaymentGatewayResult.Failure.INVALID_RESPONSE);
        assertThat(client.findByProviderOrderId(ORDER_ID).kind()).isEqualTo(PaymentGatewayResult.Kind.NOT_FOUND);
        assertThat(pg.lookupCalls()).isEqualTo(2);
        assertThat(pg.createCalls()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {202, 400, 401, 403, 422, 429, 500, 503})
    void lookupHttpFailureDoesNotBecomeAFailedPayment(int status) {
        pg.replyNext(status, rejection());

        assertUnknown(client.findByTransactionKey("TEST-TX-42"), PaymentGatewayResult.Failure.HTTP_STATUS);
        assertThat(pg.createCalls()).isZero();
        assertThat(pg.lookupCalls()).isEqualTo(1);
    }

    @Test
    void orderLookup404DoesNotMeanTheCreateWasRejected() {
        pg.replyNext(404, "");

        var result = client.findByProviderOrderId(ORDER_ID);

        assertUnknown(result, PaymentGatewayResult.Failure.HTTP_STATUS);
        assertThat(pg.createCalls()).isZero();
    }

    @Test
    void encodesOrderQueryAndTransactionPathAsValuesNotAdditionalUriSyntax() {
        String orderId = "LP-ORD-/?#+ &=한글";
        var created = client.create(new PaymentCreateRequest(orderId, AMOUNT, "TEST", CARD));

        var byOrder = client.findByProviderOrderId(orderId);

        assertThat(byOrder.transactions()).containsExactlyElementsOf(created.transactions());
        assertThat(pg.requests().get(1).rawQuery()).startsWith("orderId=")
            .contains("%2F", "%3F", "%23", "%2B", "%26", "%3D")
            .doesNotContain("한글", " ");

        String transactionKey = "TEST/TX?one#two +&";
        pg.replyNext(200, transaction(transactionKey, ORDER_ID, AMOUNT, "SUCCESS").toString());
        var byKey = client.findByTransactionKey(transactionKey);

        assertThat(byKey.kind()).isEqualTo(PaymentGatewayResult.Kind.OBSERVED);
        assertThat(pg.requests().getLast().rawPath())
            .isEqualTo("/api/v1/payments/TEST%2FTX%3Fone%23two%20%2B%26");
        assertThat(pg.requests().getLast().rawQuery()).isNull();
    }

    @Test
    void responseLossLeavesOneExternalTransactionAndNeverRetriesCreate() {
        pg.nextCreateMode(LocalTestPg.CreateMode.LOSE_RESPONSE_AFTER_CREATE);

        var result = client.create(request());

        assertUnknown(result, PaymentGatewayResult.Failure.NETWORK);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.transactionCount()).isEqualTo(1);
        var recovered = client.findByProviderOrderId(ORDER_ID);
        assertThat(recovered.kind()).isEqualTo(PaymentGatewayResult.Kind.OBSERVED);
        assertThat(recovered.transactions()).hasSize(1);
        assertThat(pg.createCalls()).isEqualTo(1);
    }

    @Test
    void doesNotFollowRedirectsOrReplayTheCreateBody() throws Exception {
        try (var otherPg = new LocalTestPg()) {
            pg.replyNextRedirect(otherPg.baseUri().resolve("/api/v1/payments"));

            var result = client.create(request());

            assertUnknown(result, PaymentGatewayResult.Failure.HTTP_STATUS);
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(otherPg.createCalls()).isZero();
            assertThat(otherPg.requests()).isEmpty();
            assertThat(pg.transactionCount()).isZero();
        }
    }

    @Test
    void responseTimeoutDoesNotEraseTheExternalTransactionOrResendCreate() throws Exception {
        client.close();
        client = new HttpTestPgClient(pg.baseUri(), pg.callbackUri(), MAPPER,
            new PgHttpTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofMillis(150), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(2)));
        pg.nextCreateMode(LocalTestPg.CreateMode.HOLD_RESPONSE_AFTER_CREATE);

        var result = client.create(request());

        assertUnknown(result, PaymentGatewayResult.Failure.TIMEOUT);
        assertThat(pg.awaitCreate(Duration.ofSeconds(1))).isTrue();
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.transactionCount()).isEqualTo(1);
        pg.releaseResponses();
        var recovered = client.findByProviderOrderId(ORDER_ID);
        assertThat(recovered.kind()).isEqualTo(PaymentGatewayResult.Kind.OBSERVED);
        assertThat(recovered.transactions()).hasSize(1);
        assertThat(pg.createCalls()).isEqualTo(1);
    }

    @Test
    void recreatingTheClientCanRecoverWithoutResettingPgStateOrCreatingAgain() throws Exception {
        var created = client.create(request()).transactions().getFirst();
        pg.setStatus(created.transactionKey(), "SUCCESS");
        client.close();
        client = new HttpTestPgClient(pg.baseUri(), pg.callbackUri(), MAPPER, PgHttpTimeouts.defaults());

        var recovered = client.findByTransactionKey(created.transactionKey());

        assertThat(recovered.transactions()).containsExactly(new PaymentTransaction(
            "135135", ORDER_ID, created.transactionKey(), AMOUNT, PaymentTransaction.Status.SUCCESS));
        assertThat(pg.transactionCount()).isEqualTo(1);
        assertThat(pg.createCalls()).isEqualTo(1);
    }

    @Test
    void diagnosticObjectsDoNotExposeTheSyntheticCardOrInvalidResponseBody() {
        String syntheticCard = "TEST-DO-NOT-PERSIST-OR-LOG";
        pg.expectedCard(syntheticCard);
        var command = new PaymentCreateRequest(ORDER_ID, AMOUNT, "TEST", syntheticCard);
        pg.replyNext(202, "{\"cardNo\":\"" + syntheticCard + "\"}");

        var result = client.create(command);

        assertUnknown(result, PaymentGatewayResult.Failure.INVALID_RESPONSE);
        assertThat(command.toString()).doesNotContain(syntheticCard);
        assertThat(result.toString()).doesNotContain(syntheticCard);
        assertThat(pg.requests().toString()).doesNotContain(syntheticCard);
        assertThat(pg.requests().getFirst().cardMatchesExpected()).isTrue();
    }

    private static PaymentCreateRequest request() {
        return new PaymentCreateRequest(ORDER_ID, AMOUNT, "TEST", CARD);
    }

    private static String rejection() {
        return MAPPER.createObjectNode().put("code", "TEST_PG_REJECTED_BEFORE_CREATE")
            .put("orderId", ORDER_ID).put("amount", AMOUNT).put("transactionCreated", false).toString();
    }

    private static ObjectNode transaction(String key, String orderId, long amount, String status) {
        return MAPPER.createObjectNode().put("transactionKey", key).put("orderId", orderId)
            .put("amount", amount).put("status", status);
    }

    private static String without(ObjectNode baseline, String field) {
        var copy = baseline.deepCopy();
        copy.remove(field);
        return copy.toString();
    }

    private static void assertUnknown(PaymentGatewayResult result, PaymentGatewayResult.Failure failure) {
        assertThat(result.kind()).isEqualTo(PaymentGatewayResult.Kind.UNKNOWN);
        assertThat(result.failure()).isEqualTo(failure);
        assertThat(result.transactions()).isEmpty();
    }
}
