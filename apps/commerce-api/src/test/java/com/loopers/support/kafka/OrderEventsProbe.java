package com.loopers.support.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.loopers.kafka.test.TopicReader;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;

// order-events topic을 eventId로 읽는다. 테스트마다 주문 ID가 다시 1부터 시작하므로
// 장면 시작 전 끝 위치(mark)부터만 읽는다. "없음"은 같은 producer가 나중에 보낸 다른 주문의 record(경계)가 도착할 때까지
// 읽은 뒤 판단한다. 한 partition 안에서는 보낸 순서대로 쌓이므로 경계보다 먼저 보낸 record는 경계 앞에 있다.
public class OrderEventsProbe implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TopicReader reader;

    public OrderEventsProbe(String bootstrapServers, String topic) {
        this.reader = new TopicReader(bootstrapServers, topic, OrderEventsProbe::eventId);
    }

    public long mark() {
        return reader.mark();
    }

    public ConsumerRecord<String, String> awaitEvent(long from, String eventId) {
        List<ConsumerRecord<String, String>> records = reader.readUntil(from, found -> indexOf(found, eventId) >= 0,
            "eventId=" + eventId + " record 도착");
        return records.get(indexOf(records, eventId));
    }

    public List<ConsumerRecord<String, String>> awaitEvents(long from, String eventId, int count) {
        List<ConsumerRecord<String, String>> records = reader.readUntil(from,
            found -> found.stream().filter(record -> eventId.equals(eventId(record))).count() >= count,
            "eventId=" + eventId + " record " + count + "건 도착");
        return records.stream().filter(record -> eventId.equals(eventId(record))).toList();
    }

    // 경계 record까지 읽어 그 사이(경계 포함)의 record를 돌려준다.
    public List<ConsumerRecord<String, String>> readThrough(long from, String boundaryEventId) {
        List<ConsumerRecord<String, String>> records = reader.readUntil(from,
            found -> indexOf(found, boundaryEventId) >= 0, "경계 eventId=" + boundaryEventId + " record 도착");
        return records.subList(0, indexOf(records, boundaryEventId) + 1);
    }

    public static String eventId(ConsumerRecord<String, String> record) {
        return json(record).path("eventId").asText();
    }

    public static JsonNode json(ConsumerRecord<String, String> record) {
        try {
            return JSON.readTree(record.value());
        } catch (Exception e) {
            throw new IllegalStateException("record 값이 JSON이 아닙니다. offset=" + record.offset(), e);
        }
    }

    public static JsonNode json(String value) {
        try {
            return JSON.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON이 아닙니다.", e);
        }
    }

    @Override
    public void close() {
        reader.close();
    }

    private static int indexOf(List<ConsumerRecord<String, String>> records, String eventId) {
        for (int index = 0; index < records.size(); index++) {
            if (eventId.equals(eventId(records.get(index)))) {
                return index;
            }
        }
        return -1;
    }
}
