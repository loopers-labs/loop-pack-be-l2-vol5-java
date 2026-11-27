dependencies {
    // spring
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // monitoring
    implementation("io.micrometer:micrometer-registry-prometheus")
    // tracing (W10 D-12): OpenTelemetry bridge + OTLP 전송. 전송처(management.otlp.tracing.endpoint)를 주지 않으면 보내지 않는다
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    // Slack Appender
    implementation("com.github.maricn:logback-slack-appender:${project.properties["slackAppenderVersion"]}")
}
