import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

dependencies {
    implementation(project(":atlas-common"))

    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.11")

    implementation("com.github.docker-java:docker-java:3.5.1")
    implementation("org.jline:jline-terminal:3.21.0")
    implementation("org.jline:jline-reader:3.21.0")

    implementation("io.vertx:vertx-core:4.5.1")
    implementation("io.vertx:vertx-web:4.5.1")
    implementation("io.vertx:vertx-web-openapi:4.5.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.16.1")
    implementation("io.swagger.core.v3:swagger-annotations:2.2.19")
    implementation("io.swagger.core.v3:swagger-core:2.2.19")
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "be.esmay.atlas.base.AtlasBase"
    }
}

tasks.named<ShadowJar>("shadowJar") {
    dependsOn(":atlas-common:shadowJar")
}
