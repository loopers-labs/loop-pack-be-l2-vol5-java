package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckoutIncidentEvidenceTest {
    private static final String TIME = "2026-11-26T12:00:00.001+09:00";
    private static final String GATEWAY = TIME + " INFO 1 --- [test] c.l.a.order.PaymentGatewayCall : ";
    private static final String APPLY = TIME + " INFO 1 --- [test] c.l.a.order.PaymentApplicationLog : ";

    @Test
    void selectsOnlyCorrelatedLostResponsesAndRejectsSuccessApplicationOrExtraUntrustedFields() {
        UUID lost = UUID.randomUUID();
        UUID rolledBack = UUID.randomUUID();
        UUID noCommit = UUID.randomUUID();
        Map<Long, UUID> orders = Map.of(10L, lost, 11L, rolledBack, 12L, noCommit);
        String captured = String.join("\n",
            createFailure(10, lost), applied(10, lost, "UNKNOWN", "COMMITTED"),
            createFailure(11, rolledBack), applied(11, rolledBack, "UNKNOWN", "COMMITTED"),
            applied(11, rolledBack, "SUCCESS", "ROLLED_BACK"),
            createFailure(12, noCommit), applied(12, UUID.randomUUID(), "UNKNOWN", "COMMITTED"),
            createFailure(90, lost), createFailure(10, lost).replace("orderId=10", "orderId=18446744073709551616"),
            createFailure(10, lost) + " cardNo=private-input");

        List<String> lines = CheckoutIncidentEvidence.orderLogs(captured, orders);

        assertThat(lines).hasSize(6).allMatch(line -> line.startsWith(TIME + " payment_"));
        assertThat(String.join("\n", lines)).doesNotContain("private-input", "orderId=90");
        assertThat(CheckoutIncidentEvidence.lostResponseCandidates(lines, orders)).containsExactly(10L);
    }

    @Test
    void sealedFaultRejectsOverwriteAndDetectsLaterMutation(@TempDir Path directory) throws Exception {
        var evidence = new CheckoutIncidentEvidence(directory, new ObjectMapper());
        String raw = "commerce_payment_unknown 1.0\n";
        evidence.phase("fault", "{\"status\":\"UP\"}", raw, Map.of("orders", List.of(10L)), List.of());
        evidence.diagnosis(Set.of(10L), Set.of(10L), Set.of(10L));
        var sealed = evidence.sealFault();

        assertThat(Files.readString(directory.resolve("fault/prometheus.txt"))).isEqualTo(raw);
        assertThatThrownBy(() -> evidence.phase("fault", "other", "other", List.of(), List.of()))
            .isInstanceOf(FileAlreadyExistsException.class);
        evidence.verifyFault(sealed);
        Files.writeString(directory.resolve("fault/prometheus.txt"), "changed");
        assertThatThrownBy(() -> evidence.verifyFault(sealed)).isInstanceOf(AssertionError.class);
    }

    private String createFailure(long order, UUID attempt) {
        return GATEWAY + "payment_gateway_result source=CONFIRM operation=CREATE " + identity(order, attempt)
            + " result=UNKNOWN reason=NETWORK pgStatus=NONE elapsedMs=2";
    }

    private String applied(long order, UUID attempt, String observation, String outcome) {
        return APPLY + "payment_apply phase=COMPLETED observation=" + observation + " " + identity(order, attempt)
            + " decision=APPLIED txOutcome=" + outcome;
    }

    private String identity(long order, UUID attempt) {
        return "orderId=" + order + " attemptKey=" + attempt + " providerOrderId=LP-ORD-%010d".formatted(order)
            + " transactionKey=NONE";
    }
}
