package com.loopers.crossapp;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

final class IncidentEvidence {
    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    final Instant startedAt = Instant.now();
    final String runId = RUN_TIME.format(startedAt) + "-" + UUID.randomUUID().toString().substring(0, 8);
    final Path directory;

    IncidentEvidence() throws IOException {
        Path root = Files.createDirectories(Path.of("build", "incident-evidence"));
        directory = Files.createDirectory(root.resolve(runId));
        System.out.println("Incident evidence: " + directory.toAbsolutePath());
    }

    void write(ObjectMapper mapper, String file, Object value) throws IOException {
        Files.write(directory.resolve(file), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value), CREATE_NEW, WRITE);
    }

    void sealFault() throws IOException, NoSuchAlgorithmException {
        byte[] bytes = Files.readAllBytes(directory.resolve("fault.json"));
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Files.writeString(directory.resolve("fault.json.sha256"), checksum + "  fault.json\n", CREATE_NEW, WRITE);
    }
}
