dependencies {
    implementation(project(":atlas-common"))

    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.11")
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "be.esmay.atlas.base.AtlasBase"
    }
}

