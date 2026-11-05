plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api("org.springframework.kafka:spring-kafka")

    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:kafka")
    // IDL(protobuf)의 필드 번호 호환성을 보여 주는 학습 테스트 전용. 제품 메시지는 JSON이다.
    testImplementation("com.google.protobuf:protobuf-java:${project.properties["protobufJavaVersion"]}")

    testFixturesImplementation("org.testcontainers:kafka")
}
