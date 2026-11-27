dependencies {
    // spring
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // monitoring
    implementation("io.micrometer:micrometer-registry-prometheus")
    // Slack Appender
    implementation("com.github.maricn:logback-slack-appender:${project.properties["slackAppenderVersion"]}")
}
