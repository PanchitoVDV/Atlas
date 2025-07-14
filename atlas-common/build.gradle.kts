dependencies {
    // Network dependencies
    implementation("io.netty:netty-all:4.1.100.Final")
    
    // JSON processing
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Logging (need for packet encoder/decoder)
    implementation("org.slf4j:slf4j-api:2.0.9")
    
    // Lombok for annotations
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
}