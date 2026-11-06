plugins {
    checkstyle
}

checkstyle {
    toolVersion = "10.26.1"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

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
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${project.properties["springDocOpenApiVersion"]}")

    // resilience (PG 조회 재시도·circuit breaker — 스타터·AOP 대신 adapter 안에서 직접 조립한다, docs/week5/design.md D-029)
    implementation("io.github.resilience4j:resilience4j-retry:2.3.0")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.3.0")
    implementation("io.github.resilience4j:resilience4j-ratelimiter:2.3.0")

    // security (관리자 경로 인가)
    implementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework.security:spring-security-test")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // architecture
    testImplementation("com.tngtech.archunit:archunit:1.5.0")

    // acceptance (실제 포트로 HTTP 요청 — 버전은 Spring Boot가 관리)
    testImplementation("io.rest-assured:rest-assured")

    // 테스트 PG fixture (docs/week5/design.md D-033) — standalone은 내부 Jetty·Jackson을 감싸 Spring Boot와 충돌하지 않는다
    testImplementation("org.wiremock:wiremock-standalone:3.13.1")

    // 주문 확정 이벤트 발행 테스트용 broker (docs/week6/decisions.md D-24) — 버전은 Spring Boot가 관리한다
    testImplementation("org.testcontainers:kafka")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
}

// 배달 E2E가 consumer를 별도 프로세스로 띄울 streamer 실행 jar(docs/week7/decisions.md D-10, D-15).
// 의존성으로 넣지 않는다 — streamer가 api 테스트 클래스패스에 들어오면 @EntityScan("com.loopers")로 모든 api 테스트가
// streamer의 listener·엔티티까지 띄운다. jar 경로만 시스템 프로퍼티로 넘기고, 없으면 E2E가 건너뛰지 않고 실패한다.
val streamerBootJar = project(":apps:commerce-streamer").tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar")
val streamerBootJarFile = streamerBootJar.flatMap { it.archiveFile }

// api와 streamer가 같은 계약 JSON을 읽는다(docs/week6/decisions.md D-24). 테스트는 이 경로로 파일을 찾는다.
tasks.test {
    systemProperty("contract.dir", rootProject.file("docs/week6/contract").absolutePath)
    dependsOn(streamerBootJar)
    // jar가 바뀌면 테스트를 다시 돌린다. 경로는 컴퓨터마다 다르므로 내용만 본다.
    inputs.file(streamerBootJarFile).withPropertyName("streamerBootJar").withPathSensitivity(PathSensitivity.NONE)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dstreamer.bootJar=" + streamerBootJarFile.get().asFile.absolutePath)
    })
    // 6주차 재현 + 복구 E2E는 과거 버전 빌드가 필요해 check에서 뺀다(docs/week7/decisions.md D-14). w6RecoveryTest로 따로 돌린다.
    useJUnitPlatform {
        excludeTags("w6-recovery")
    }
}

// ---------------------------------------------------------------- 6주차 재현(docs/week7/decisions.md D-10, D-13, D-14)

// 6주차 버전. jar 옆 COMMIT 파일에는 이 이름을 풀어 쓴 전체 해시를 적는다 — 같은 해시면 다시 빌드하지 않는다(D-13).
val w6Revision = "ad7f2c8"
val w6ApiDir = layout.buildDirectory.dir("w6-api")
val w6ApiJar = w6ApiDir.map { it.file("commerce-api-w6.jar") }

// 6주차 api 실행 jar를 준비한다. 기본 test·check 그래프에는 넣지 않는다 — w6RecoveryTest만 의존한다.
// jar가 있고 COMMIT이 맞으면 아무것도 하지 않는다. 아니면 6주차 커밋을 worktree로 꺼내 그 안의 Gradle wrapper로 bootJar를
// 빌드하고, 복사한 뒤 worktree를 지운다. 손으로 할 일이 없어 새로 clone한 저장소에서도 그대로 돈다.
val prepareW6ApiJar by tasks.registering {
    description = "6주차($w6Revision) commerce-api 실행 jar를 worktree에서 빌드해 build/w6-api에 둔다. docs/week7/decisions.md D-13"
    group = "build"
    // 최신 여부는 이 작업이 COMMIT 파일로 직접 판단한다.
    outputs.upToDateWhen { false }
    val repoDir = rootProject.projectDir
    val outDir = w6ApiDir.get().asFile
    val jarFile = w6ApiJar.get().asFile
    doLast {
        val windows = System.getProperty("os.name").lowercase().contains("windows")

        fun run(dir: File, log: File?, vararg command: String): String {
            val builder = ProcessBuilder(*command).directory(dir).redirectErrorStream(true)
            if (log != null) {
                builder.redirectOutput(log)
            }
            val process = builder.start()
            val output = if (log == null) process.inputStream.bufferedReader().readText() else ""
            val exit = process.waitFor()
            if (exit != 0) {
                val detail = if (log == null) output else log.readLines().takeLast(60).joinToString("\n")
                throw GradleException("명령 실패(exit=$exit): ${command.joinToString(" ")}\n$detail")
            }
            return output
        }

        fun git(vararg args: String): String = run(repoDir, null, "git", *args)

        val commit = git("rev-parse", "$w6Revision^{commit}").trim()
        val commitFile = File(outDir, "COMMIT")
        if (jarFile.isFile && commitFile.isFile && commitFile.readText().trim() == commit) {
            logger.lifecycle("6주차 jar 재사용: $jarFile (COMMIT $commit)")
            return@doLast
        }

        outDir.mkdirs()
        jarFile.delete()
        commitFile.delete()
        val worktree = File(outDir, "wt")

        fun removeWorktree() {
            if (worktree.exists()) {
                runCatching { git("worktree", "remove", "--force", worktree.absolutePath) }
                    .onFailure { logger.warn("worktree 제거 실패, 폴더를 직접 지운다: ${it.message}") }
                worktree.deleteRecursively()
            }
            git("worktree", "prune")
        }

        // 앞선 실행이 중간에 끊겨 남은 worktree를 먼저 치운다.
        removeWorktree()
        logger.lifecycle("6주차 jar 빌드: $w6Revision($commit)을 $worktree 에 꺼낸다")
        git("worktree", "add", "--detach", worktree.absolutePath, commit)
        try {
            val wrapper = File(worktree, if (windows) "gradlew.bat" else "gradlew")
            if (!windows) {
                wrapper.setExecutable(true)
            }
            val buildLog = File(outDir, "build-w6.log")
            run(worktree, buildLog, wrapper.absolutePath, ":apps:commerce-api:bootJar", "-x", "test", "--no-daemon", "--console=plain")
            val built = File(worktree, "apps/commerce-api/build/libs")
                .listFiles { file -> file.name.endsWith(".jar") && !file.name.endsWith("-plain.jar") }
                .orEmpty()
            if (built.size != 1) {
                throw GradleException("6주차 실행 jar를 하나로 특정하지 못했다: ${built.map { it.name }}")
            }
            built[0].copyTo(jarFile, overwrite = true)
            // COMMIT은 jar 복사가 끝난 뒤에 쓴다 — 중간에 끊기면 다음 실행이 다시 빌드한다.
            commitFile.writeText(commit + "\n")
            logger.lifecycle("6주차 jar 준비 완료: $jarFile")
        } finally {
            removeWorktree()
        }
    }
}

// 6주차 재현 + 복구 E2E만 실행한다(@Tag("w6-recovery")). streamer의 experimentTest(W6 D-21)와 같은 방식으로 check에서 뺀다.
// jar 경로는 시스템 프로퍼티로 넘기고, 없으면 테스트가 건너뛰지 않고 실패한다.
val w6RecoveryTest by tasks.registering(Test::class) {
    description = "@Tag(\"w6-recovery\") 6주차 누락 재현 + Outbox 복구 E2E만 실행한다(check에 묶지 않는다). docs/week7/decisions.md D-14"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    maxParallelForks = 1
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("spring.profiles.active", "test")
    systemProperty("contract.dir", rootProject.file("docs/week6/contract").absolutePath)
    jvmArgs("-Xshare:off")
    useJUnitPlatform {
        includeTags("w6-recovery")
    }
    dependsOn(prepareW6ApiJar, streamerBootJar)
    // 재현은 부를 때마다 실제로 다시 돈다 — 이전 결과를 재사용하지 않는다.
    outputs.upToDateWhen { false }
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dw6.apiJar=" + w6ApiJar.get().asFile.absolutePath,
            "-Dstreamer.bootJar=" + streamerBootJarFile.get().asFile.absolutePath,
        )
    })
}
