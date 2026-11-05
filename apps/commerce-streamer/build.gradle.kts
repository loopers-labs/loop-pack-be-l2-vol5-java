dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":modules:kafka"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // web
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation(testFixtures(project(":modules:kafka")))
}

// W7 E2E(commerce-api의 e2eTest source set)가 실제 구매 활동 consumer를 테스트 classpath로 불러오도록 plain jar를 만든다 (docs/week7/design.md 5절 결정 B).
// 제품 산출물(bootJar)은 그대로다. application.yml은 빼서 불러오는 쪽(commerce-api)의 설정을 가리지 않게 한다.
tasks.named<Jar>("jar") {
    enabled = true
    archiveClassifier.set("plain")
    exclude("application.yml")
}
