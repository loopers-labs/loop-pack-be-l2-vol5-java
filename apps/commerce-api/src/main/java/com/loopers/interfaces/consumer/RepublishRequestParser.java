package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;

// 재발행 명령 v1(설계 27절). 모르는 필드는 무시하고, 모르는 종류·버전은 멈춤 대상, 고칠 수 없는 record는 Invalid로 나눈다.
// 공용 ObjectMapper 설정과 무관하게 계약 필드만 직접 읽는다.
@Component
public class RepublishRequestParser {
    static final String COMMAND_TYPE = "RepublishOrderConfirmed";
    static final int SCHEMA_VERSION = 1;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public RepublishRequestParseResult parse(String key, byte[] payload) {
        JsonNode command;
        try {
            command = JSON.readTree(payload);
        } catch (IOException e) {
            return new RepublishRequestParseResult.Invalid("JSON이 아님: " + e.getClass().getSimpleName());
        }
        if (command == null || !command.isObject()) {
            return new RepublishRequestParseResult.Invalid("JSON 객체가 아님");
        }
        JsonNode commandType = command.get("commandType");
        JsonNode schemaVersion = command.get("schemaVersion");
        if (commandType == null || !commandType.isTextual() || schemaVersion == null || !schemaVersion.canConvertToInt()) {
            return new RepublishRequestParseResult.Invalid("commandType·schemaVersion 누락 또는 형식 오류");
        }
        if (!COMMAND_TYPE.equals(commandType.asText()) || schemaVersion.asInt() != SCHEMA_VERSION) {
            return new RepublishRequestParseResult.Unsupported(
                "모르는 명령 commandType=" + commandType.asText() + ", schemaVersion=" + schemaVersion.asInt());
        }
        JsonNode orderId = command.get("orderId");
        if (orderId == null || !orderId.isIntegralNumber() || !orderId.canConvertToLong()) {
            return new RepublishRequestParseResult.Invalid("orderId 누락 또는 형식 오류");
        }
        if (!String.valueOf(orderId.asLong()).equals(key)) {
            return new RepublishRequestParseResult.Invalid("key와 orderId 불일치: key=" + key + ", orderId=" + orderId.asLong());
        }
        JsonNode requestedAt = command.get("requestedAt");
        if (requestedAt == null || !requestedAt.isTextual() || !isInstant(requestedAt.asText())) {
            return new RepublishRequestParseResult.Invalid("requestedAt 누락 또는 형식 오류");
        }
        return new RepublishRequestParseResult.Parsed(orderId.asLong());
    }

    private static boolean isInstant(String value) {
        try {
            Instant.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
