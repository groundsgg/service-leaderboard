plugins {
    id("gg.grounds.root") version "0.1.1"
    id("io.quarkus") version "3.36.0"
}

// The OpenAPI snapshot published to groundsgg/api-reference. Quarkus only writes
// `build/generated/openapi/openapi.json` on a production build, and it appends to
// whatever is already there — so a stale file from an earlier build would ship
// endpoints that no longer exist. Clearing first is what makes the snapshot a
// statement about this commit.
val cleanProductionOpenApi =
    tasks.register<Delete>("cleanProductionOpenApi") {
        delete(layout.buildDirectory.dir("generated/openapi"))
        delete(layout.buildDirectory.dir("quarkus"))
        delete(layout.buildDirectory.dir("quarkus-app"))
        delete(layout.buildDirectory.dir("quarkus-build"))
    }

// The ordering covers every `quarkus*` task, not just `quarkusBuild`. The clean
// removes `build/quarkus`, which is where `quarkusGenerateAppModel` writes the
// application model that `quarkusBuild` then reads — constraining only the
// latter lets Gradle run the generator first and delete its output underneath
// it, which fails as "input file does not exist" in any single invocation that
// asks for both `test` and this snapshot.
tasks
    .matching { it.name.startsWith("quarkus") }
    .configureEach { mustRunAfter(cleanProductionOpenApi) }

tasks.register<Copy>("generateOpenApiSnapshot") {
    group = "documentation"
    dependsOn(cleanProductionOpenApi, tasks.named("quarkusBuild"))
    from(layout.buildDirectory.file("generated/openapi/openapi.json"))
    into(layout.buildDirectory.dir("api-reference"))
    rename { "openapi.json" }
}

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/groundsgg/*")
        credentials {
            username = providers.gradleProperty("github.user").get()
            password = providers.gradleProperty("github.token").get()
        }
    }
}

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.30.8"))
    implementation("io.quarkus:quarkus-arc")
    // The public API. HTTP is the only transport.
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-smallrye-openapi")
    // Kotlin data classes as request bodies: without this module Jackson cannot
    // see constructor parameter names, so every field arrives null.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-kotlin")
    // JWT validation for incoming calls. Callers attach the projected
    // ServiceAccount token (aud=grounds-services); WorkloadAuthenticator
    // verifies it against the cluster JWKS for both transports.
    implementation("com.nimbusds:nimbus-jose-jwt:9.41.1")
    // OpenTelemetry — server-side instrumentation + OTLP exporter to Alloy.
    implementation("io.quarkus:quarkus-opentelemetry")
    // Prometheus metrics on /q/metrics — JVM, HTTP and the Agroal pool.
    implementation("io.quarkus:quarkus-micrometer-registry-prometheus")

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-junit5-mockito")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.2.2")
    testImplementation("org.testcontainers:postgresql:1.21.5")
    testImplementation("org.testcontainers:junit-jupiter:1.21.5")
}
