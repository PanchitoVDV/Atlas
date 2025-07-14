import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

dependencies {
    implementation(project(":atlas-common"))
    
    // Velocity API
    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    
    // Netty for network client
    implementation("io.netty:netty-all:4.1.100.Final")
}

tasks.named("compileJava") {
    dependsOn(":atlas-common:shadowJar")
}

tasks.named<ShadowJar>("shadowJar") {
    dependsOn(":atlas-common:shadowJar")
}