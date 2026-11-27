package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

final class IncidentEvidence {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Path ROOT = Path.of("build", "incident-evidence");
    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final String runId;
    private final Path directory;

    private IncidentEvidence(String runId, Path directory) {
        this.runId = runId;
        this.directory = directory;
    }

    static IncidentEvidence start() {
        String runId = STAMP.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            return new IncidentEvidence(runId, Files.createDirectories(ROOT.resolve(runId)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String runId() {
        return runId;
    }

    void record(String phase, Map<String, Object> facts) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("runId", runId);
        record.put("phase", phase);
        record.put("observedAt", Instant.now().toString());
        record.putAll(facts);
        try {
            writeNew(phase + ".json", JSON.writeValueAsString(record));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void recordRaw(String fileName, String text) {
        writeNew(fileName, text);
    }

    void recordFailure(String phase, Throwable failure) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("failedDuring", phase);
        facts.put("failure", failure.getClass().getName());
        facts.put("message", String.valueOf(failure.getMessage()));
        record("failure", facts);
    }

    JsonNode read(String phase) {
        try {
            return JSON.readTree(Files.readString(directory.resolve(phase + ".json")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String sha256(String fileName) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(directory.resolve(fileName))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    List<String> contents() {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(IncidentEvidence::readText).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeNew(String fileName, String text) {
        try {
            Files.writeString(directory.resolve(fileName), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
