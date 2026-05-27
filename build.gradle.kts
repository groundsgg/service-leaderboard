plugins {
    id("gg.grounds.root") version "0.1.1"
    id("io.quarkus") version "3.30.6"
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
    implementation("io.quarkus:quarkus-grpc")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-kotlin")
    // JWT validation for incoming gRPC calls. SDK attaches the
    // projected ServiceAccount token (aud=grounds-services); the
    // interceptor reads + verifies it against k8s JWKS.
    implementation("com.nimbusds:nimbus-jose-jwt:9.41.1")
    implementation("gg.grounds:library-grpc-contracts-leaderboard:main-SNAPSHOT")

    compileOnly("com.google.protobuf:protobuf-kotlin")

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-junit5-mockito")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.2.2")
    testImplementation("org.testcontainers:postgresql:1.21.5")
    testImplementation("org.testcontainers:junit-jupiter:1.21.5")
}

sourceSets { main { java { srcDirs("build/classes/java/quarkus-generated-sources/grpc") } } }

tasks
    .matching { it.name == "kaptGenerateStubsKotlin" }
    .configureEach {
        dependsOn("quarkusGenerateCode")
        dependsOn("quarkusGenerateCodeDev")
    }
