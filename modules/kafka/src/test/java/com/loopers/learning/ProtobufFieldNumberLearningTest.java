package com.loopers.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import org.junit.jupiter.api.Test;

import static com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32;
import static com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT64;
import static com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * IDL(protobuf)이 메시지 호환성을 어떻게 다루는지 보여 주는 학습 테스트다. 제품 메시지는 JSON이고 이 형식을 쓰지 않는다.
 * <p>
 * 실제로는 아래처럼 .proto 파일을 쓰고, protoc가 producer·consumer 양쪽의 Java 클래스를 생성한다.
 * <pre>
 * message Item {
 *   int64 product_id = 1;
 *   int32 quantity   = 2;
 * }
 * </pre>
 * 빌드 플러그인을 들이지 않으려고 같은 정의를 코드로 만들고 {@link DynamicMessage}로 읽고 쓴다.
 * 바이트에는 필드 이름이 아니라 <b>번호와 값</b>만 실린다는 점이 핵심이다.
 */
class ProtobufFieldNumberLearningTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 이름만 바꾼 v2(quantity → qty)로 쓴 바이트를 v1이 읽는다. 번호 2가 같으므로 값이 그대로 읽힌다. */
    @Test
    void renamingFieldKeepsCompatibilityBecauseOnlyNumbersAreOnTheWire() throws Exception {
        Descriptor v1 = item("quantity", TYPE_INT32, false);
        Descriptor v2 = item("qty", TYPE_INT32, false);

        byte[] wire = DynamicMessage.newBuilder(v2)
            .setField(v2.findFieldByName("product_id"), 7L)
            .setField(v2.findFieldByName("qty"), 5)
            .build().toByteArray();
        DynamicMessage read = DynamicMessage.parseFrom(v1, wire);

        assertThat(read.getField(v1.findFieldByName("quantity"))).isEqualTo(5);

        // 같은 이름 변경을 JSON으로 하면 이름이 곧 계약이라 consumer가 값을 찾지 못한다.
        var renamedJson = JSON.readTree("{\"productId\":7,\"qty\":5}");
        assertThat(renamedJson.path("quantity").isMissingNode()).isTrue();
    }

    /** v2가 새 필드(번호 5)를 추가해도 v1은 아는 값을 읽고, 모르는 번호는 버리지 않고 보관한다. */
    @Test
    void addedFieldIsSkippedByOldReaderButKept() throws Exception {
        Descriptor v1 = item("quantity", TYPE_INT32, false);
        Descriptor v2 = item("quantity", TYPE_INT32, true);

        byte[] wire = DynamicMessage.newBuilder(v2)
            .setField(v2.findFieldByName("product_id"), 7L)
            .setField(v2.findFieldByName("quantity"), 5)
            .setField(v2.findFieldByName("cancelled_quantity"), 1)
            .build().toByteArray();
        DynamicMessage read = DynamicMessage.parseFrom(v1, wire);

        assertThat(read.getField(v1.findFieldByName("quantity"))).isEqualTo(5);
        assertThat(read.getUnknownFields().hasField(5)).isTrue();
        // 그대로 다시 쓰면 모르는 필드도 함께 나간다.
        assertThat(DynamicMessage.parseFrom(v2, read.toByteArray()).getField(v2.findFieldByName("cancelled_quantity")))
            .isEqualTo(1);
    }

    /**
     * 같은 번호의 타입을 바꾸면 v1은 그 값을 자기 필드로 읽지 못한다. 예외 없이 기본값 0이 되어 조용히 틀린다.
     * 그래서 지운 번호는 reserved로 막아 다른 뜻으로 다시 쓰지 못하게 한다.
     */
    @Test
    void reusingNumberWithAnotherTypeSilentlyLosesTheValue() throws Exception {
        Descriptor v1 = item("quantity", TYPE_INT32, false);
        Descriptor changed = item("quantity", TYPE_STRING, false);

        byte[] wire = DynamicMessage.newBuilder(changed)
            .setField(changed.findFieldByName("product_id"), 7L)
            .setField(changed.findFieldByName("quantity"), "5")
            .build().toByteArray();
        DynamicMessage read = DynamicMessage.parseFrom(v1, wire);

        assertThat(read.getField(v1.findFieldByName("quantity"))).isEqualTo(0);
        assertThat(read.getUnknownFields().hasField(2)).isTrue();
    }

    private static Descriptor item(String quantityName, FieldDescriptorProto.Type quantityType,
                                   boolean withCancelledQuantity) throws DescriptorValidationException {
        DescriptorProto.Builder item = DescriptorProto.newBuilder().setName("Item")
            .addField(field("product_id", 1, TYPE_INT64))
            .addField(field(quantityName, 2, quantityType));
        if (withCancelledQuantity) {
            item.addField(field("cancelled_quantity", 5, TYPE_INT32));
        }
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
            .setName("item.proto").setSyntax("proto3").addMessageType(item).build();
        return FileDescriptor.buildFrom(file, new FileDescriptor[0]).findMessageTypeByName("Item");
    }

    private static FieldDescriptorProto field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
            .setName(name).setNumber(number).setType(type)
            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
            .build();
    }
}
