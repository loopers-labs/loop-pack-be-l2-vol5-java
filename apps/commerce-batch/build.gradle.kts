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

    // W9 batch insert 성능 실험 (docs/week9/perf): Querydsl-SQL은 실험 테스트에서만 쓴다
    testImplementation("com.querydsl:querydsl-sql")
    testImplementation("com.querydsl:querydsl-sql-spring")
}

// W9 batch insert 성능 실험. 몇 시간까지 걸리는 측정용이라 평소 test에서는 빼고 perfRun으로만 돈다.
tasks.test {
    useJUnitPlatform {
        excludeTags("perf")
    }
}

tasks.register<Test>("perfRun") {
    description = "W9 batch insert 성능 실험 — 결과는 docs/week9/perf/results (예: -Pperf.sizes=1000000 -Pperf.reps=3)"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("perf")
    }
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("spring.profiles.active", "test")
    systemProperty("perf.out", findProperty("perf.out") ?: rootProject.file("docs/week9/perf/results").absolutePath)
    listOf("perf.sizes", "perf.reps", "perf.methods").forEach { key -> findProperty(key)?.let { systemProperty(key, it) } }
    jvmArgs("-Xshare:off")
    // CPU 샘플 (어느 메서드가 시간을 쓰나): -Pperf.jfr=build/perf/insert.jfr → jfr print --events jdk.ExecutionSample
    findProperty("perf.jfr")?.let { jvmArgs("-XX:StartFlightRecording=filename=${file(it).absolutePath},settings=profile,jdk.ExecutionSample#period=1ms") }
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}
