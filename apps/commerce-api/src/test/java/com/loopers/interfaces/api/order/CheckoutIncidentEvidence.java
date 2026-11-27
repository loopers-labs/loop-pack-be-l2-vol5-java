package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Run-local evidence from the owned checkout incident, written before any state-changing recovery. */
final class CheckoutIncidentEvidence {
    private static final List<String> FAULT_FILES = List.of("recorded-at.txt", "health.json", "prometheus.txt",
        "state.json", "boundaries.log", "diagnosis.json");
    private static final String IDENTITIES = "orderId=(?<order>[0-9]+) attemptKey=(?<attempt>[0-9a-fA-F-]{36})"
        + " providerOrderId=(?:LP-ORD-[0-9]{10,19}|REDACTED|NONE) transactionKey=(?:TEST-TX-[0-9]{1,10}|REDACTED|NONE)";
    private static final Pattern GATEWAY = Pattern.compile("payment_gateway_result source=(?:CONFIRM|RECONCILE|CALLBACK|REFRESH)"
        + " operation=(?:CREATE|LOOKUP_BY_ORDER|LOOKUP_BY_KEY) " + IDENTITIES
        + " result=(?:OBSERVED|REJECTED_NO_EFFECT|NOT_FOUND|UNKNOWN|CONFLICT|NOT_SENT|ERROR)"
        + " reason=(?:NONE|TIMEOUT|NETWORK|HTTP_STATUS|INVALID_RESPONSE|CIRCUIT_OPEN|ADAPTER_EXCEPTION)"
        + " pgStatus=(?:SUCCESS|FAILED|PENDING|NONE) elapsedMs=[0-9]+");
    private static final Pattern APPLICATION = Pattern.compile("payment_apply phase=(?:STARTED|COMPLETED)"
        + " observation=(?:SUCCESS|FAILED|PENDING|UNKNOWN|CONFLICT|REJECTED_NO_EFFECT) " + IDENTITIES
        + "(?: decision=(?:APPLIED|UNCHANGED|CONFLICT|NOT_EVALUATED) txOutcome=(?:COMMITTED|ROLLED_BACK|UNKNOWN))?");
    private static final Pattern LOG_LINE = Pattern.compile("^(?<time>\\d{4}-\\d{2}-\\d{2}T[0-9:.+Z-]+)\\s+INFO\\s+"
        + ".*?\\.(?<logger>PaymentGatewayCall|PaymentApplicationLog)\\s+: (?<message>.+)$");

    private final Path directory;
    private final ObjectMapper mapper;

    CheckoutIncidentEvidence(Path directory, ObjectMapper mapper) {
        this.directory = directory;
        this.mapper = mapper;
    }

    static CheckoutIncidentEvidence start(ObjectMapper mapper) throws IOException {
        Path root = Path.of("build", "reports", "week10-checkout").toAbsolutePath();
        Files.createDirectories(root);
        Path directory = root.resolve(Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID());
        return new CheckoutIncidentEvidence(Files.createDirectory(directory), mapper);
    }

    Path directory() {
        return directory;
    }

    static List<String> orderLogs(String capturedOutput, Map<Long, UUID> orders) {
        // Keep the observed timestamp and the entire allowlisted payment event; discard arbitrary MDC and other output.
        return capturedOutput.lines().map(LOG_LINE::matcher).filter(Matcher::matches).map(line -> {
            String message = line.group("message");
            Pattern schema = line.group("logger").equals("PaymentGatewayCall") ? GATEWAY : APPLICATION;
            Matcher event = schema.matcher(message);
            if (event.matches() && matchesAttempt(event, orders)) {
                return line.group("time") + " " + message;
            }
            return "";
        }).filter(line -> !line.isEmpty()).toList();
    }

    static Set<Long> lostResponseCandidates(List<String> logs, Map<Long, UUID> orders) {
        var lost = new TreeSet<Long>();
        var committedUnknown = new TreeSet<Long>();
        var successObserved = new TreeSet<Long>();
        for (String line : logs) {
            String message = line.substring(line.indexOf(' ') + 1);
            boolean gateway = message.startsWith("payment_gateway_result ");
            Matcher event = (gateway ? GATEWAY : APPLICATION).matcher(message);
            if (!event.matches() || !matchesAttempt(event, orders)) {
                continue;
            }
            long id = Long.parseLong(event.group("order"));
            if (gateway && message.contains("source=CONFIRM operation=CREATE ")
                && message.contains("result=UNKNOWN reason=NETWORK pgStatus=NONE ")) {
                lost.add(id);
            }
            if (lost.contains(id) && message.contains("phase=COMPLETED observation=UNKNOWN ")
                && message.endsWith("decision=APPLIED txOutcome=COMMITTED")) {
                committedUnknown.add(id);
            }
            if (message.contains("pgStatus=SUCCESS ") || message.contains(" observation=SUCCESS ")) {
                successObserved.add(id);
            }
        }
        lost.retainAll(committedUnknown);
        lost.removeAll(successObserved);
        return lost;
    }

    private static boolean matchesAttempt(Matcher event, Map<Long, UUID> orders) {
        try {
            UUID expected = orders.get(Long.parseLong(event.group("order")));
            return expected != null && expected.toString().equals(event.group("attempt"));
        } catch (NumberFormatException invalidOrder) {
            return false;
        }
    }

    void phase(String name, String rawHealth, String rawPrometheus, Object safeState, List<String> logs) throws IOException {
        if (!Set.of("baseline", "fault", "recovery").contains(name)) {
            throw new IllegalArgumentException("Unknown incident phase");
        }
        Path phase = Files.createDirectory(directory.resolve(name));
        write(phase.resolve("recorded-at.txt"), Instant.now().toString());
        write(phase.resolve("health.json"), rawHealth);
        write(phase.resolve("prometheus.txt"), rawPrometheus);
        write(phase.resolve("state.json"), jsonText(safeState));
        write(phase.resolve("boundaries.log"), String.join("\n", logs) + "\n");
    }

    void diagnosis(Set<Long> candidates, Set<Long> dbUnknown, Set<Long> matchingPgSuccess) throws IOException {
        boolean supported = !dbUnknown.isEmpty() && dbUnknown.equals(candidates) && matchingPgSuccess.containsAll(candidates);
        boolean allExternallyApproved = !dbUnknown.isEmpty() && matchingPgSuccess.containsAll(dbUnknown);
        write(directory.resolve("fault/diagnosis.json"), jsonText(Map.of(
            "observedAt", Instant.now().toString(), "networkAndCommittedUnknown", new TreeSet<>(candidates),
            "committedDbUnknown", new TreeSet<>(dbUnknown), "matchingPgSuccess", new TreeSet<>(matchingPgSuccess),
            "hypotheses", List.of(
                Map.of("hypothesis", "PG still processing", "expect", "Authoritative lookup for the same account/order/amount returns PENDING",
                    "assessment", allExternallyApproved ? "Contradicted at observation: every unresolved order has a matching SUCCESS" : "Inconclusive"),
                Map.of("hypothesis", "Lost create response after approval",
                    "expect", "CREATE UNKNOWN/NETWORK, committed UNKNOWN, matching authoritative PG SUCCESS",
                    "assessment", supported ? "Supported by correlated logs, committed order data and read-only PG lookup" : "Inconclusive"),
                Map.of("hypothesis", "Local success application failed",
                    "expect", "A received SUCCESS, an application attempt and a rolled-back transaction for that attempt",
                    "assessment", supported ? "The captured CREATE saw NETWORK and the subsequent UNKNOWN committed; no successful observation in those candidates"
                        : "Inconclusive")),
            "limits", "NETWORK describes the adapter observation. The logs alone cannot locate the physical break or rule out unobserved events.")));
    }

    Map<String, String> sealFault() throws IOException {
        Map<String, String> hashes = faultHashes();
        json("fault-sha256.json", hashes);
        return Map.copyOf(hashes);
    }

    void verifyFault(Map<String, String> expected) throws IOException {
        var manifest = mapper.readTree(Files.readString(directory.resolve("fault-sha256.json")));
        if (!expected.equals(faultHashes()) || !manifest.equals(mapper.valueToTree(expected))) {
            throw new AssertionError("Preserved fault evidence changed in " + directory);
        }
    }

    private Map<String, String> faultHashes() throws IOException {
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            var hashes = new LinkedHashMap<String, String>();
            for (String name : FAULT_FILES) {
                byte[] bytes = Files.readAllBytes(directory.resolve("fault").resolve(name));
                hashes.put(name, HexFormat.of().formatHex(sha.digest(bytes)));
            }
            return hashes;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    void json(String filename, Object data) throws IOException {
        if (!filename.matches("[A-Za-z0-9_-]+\\.json")) {
            throw new IllegalArgumentException("Expected a simple evidence filename");
        }
        write(directory.resolve(filename), jsonText(data));
    }

    void failure(String phase, Throwable failure) throws IOException {
        json("failure.json", Map.of("observedAt", Instant.now().toString(), "phase", phase,
            "failureType", failure.getClass().getName(), "testFrames", Arrays.stream(failure.getStackTrace())
                .filter(frame -> frame.getClassName().startsWith("com.loopers."))
                .limit(5).map(StackTraceElement::toString).toList()));
    }

    private String jsonText(Object data) throws IOException {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
}
