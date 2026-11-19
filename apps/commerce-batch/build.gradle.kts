dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // batch
    implementation("org.springframework.boot:spring-boot-starter-batch")
    testImplementation("org.springframework.batch:spring-batch-test")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
}

// 9주차 과제 7절: commerce-api 의 crossAppTest 소스셋이 이 앱의 실제 Job·Step 클래스를 테스트 의존으로 쓰려면
// 일반 jar(-plain.jar)가 필요하다. streamer 와 같은 이유·같은 방식이며 bootJar 는 영향 없다.
tasks.jar { enabled = true }
