import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

repositories {
    maven(url = "https://jitpack.io")
}

var minestomCommit = "4fe2993057"

dependencies {
    implementation(project(":atlas-common"))
    compileOnly("net.minestom:minestom-snapshots:$minestomCommit")
    compileOnly("com.github.Jazzkuh:MinestomPlugins:be252b0184")

    implementation("io.netty:netty-all:4.1.100.Final")
}

tasks.named<ShadowJar>("shadowJar") {
    dependsOn(":atlas-common:shadowJar")
    
    // Exclude dependencies provided by Minestom (Gson, SLF4J)
    exclude("com/google/gson/**")
    exclude("org/slf4j/**")
    exclude("ch/qos/logback/**")
}

tasks.named("compileJava") {
    dependsOn(":atlas-common:shadowJar")
}