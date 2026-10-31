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
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
}

// 로컬 브로커 3대 같은 외부 환경이 필요한 재현 테스트(@Tag("experiment"))는 평소 test·check에서 뺀다.
tasks.test {
    useJUnitPlatform {
        excludeTags("experiment")
    }
}

tasks.register<Test>("experimentTest") {
    description = "외부 환경이 필요한 재현 테스트(@Tag(\"experiment\"))만 실행한다."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    maxParallelForks = 1
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("spring.profiles.active", "test")
    jvmArgs("-Xshare:off")
    useJUnitPlatform {
        includeTags("experiment")
    }
}
