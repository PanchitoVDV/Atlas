import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

repositories {
    maven("https://nexus.velocitypowered.com/repository/maven-public/")
}

dependencies {
    implementation(project(":atlas-common"))

    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")

    compileOnly("io.netty:netty-all:4.1.100.Final")
}

tasks.named("compileJava") {
    dependsOn(":atlas-common:shadowJar")
}

tasks.named<ShadowJar>("shadowJar") {
    dependsOn(":atlas-common:shadowJar")
    
    // Exclude dependencies provided by Velocity
    exclude("io/netty/**")
    exclude("com/google/gson/**")
    exclude("org/slf4j/**")
    exclude("ch/qos/logback/**")
}