dependencies {
    // Network dependencies - compileOnly since provided by server platforms
    compileOnly("io.netty:netty-all:4.1.100.Final")
    
    // JSON processing - only keep Gson, Jackson moved to atlas-base
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Logging (need for packet encoder/decoder)
    implementation("org.slf4j:slf4j-api:2.0.9")
    
    // Lombok for annotations
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
}