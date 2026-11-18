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

tasks.test {
    useJUnitPlatform {
        excludeTags("experiment", "speed-experiment")
    }
}

val experimentTest by tasks.registering(Test::class) {
    description = "Runs experiments that need a fixed heap. Not part of check."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("experiment")
    }
    maxHeapSize = "512m"
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("spring.profiles.active", "test")
    systemProperty("exp029.sizes", providers.gradleProperty("exp029.sizes").getOrElse("100000,200000,400000"))
    systemProperty("exp029.repeats", providers.gradleProperty("exp029.repeats").getOrElse("3"))
    jvmArgs(
        "-Xshare:off",
        "-Xmn16m",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=" + layout.buildDirectory.file("exp029.hprof").get().asFile.path
    )
    testLogging {
        showStandardStreams = true
    }
}

val speedExperimentTest by tasks.registering(Test::class) {
    description = "Runs timing experiments with default GC settings. Not part of check."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("speed-experiment")
    }
    maxHeapSize = "1g"
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("spring.profiles.active", "test")
    jvmArgs("-Xshare:off")
    testLogging {
        showStandardStreams = true
    }
}
