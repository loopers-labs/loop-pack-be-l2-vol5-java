import org.springframework.boot.gradle.tasks.bundling.BootJar

dependencies {
    implementation("org.wiremock:wiremock-standalone:3.13.2")
    implementation("com.fasterxml.jackson.core:jackson-databind")
}

tasks.named<BootJar>("bootJar") {
    mainClass.set("com.loopers.testpg.TestPgApplication")
}
