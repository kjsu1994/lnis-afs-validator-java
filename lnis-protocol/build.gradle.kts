plugins { `java-library` }

description = "LNIS 서버와 Windows Agent가 공유하는 protocol 및 wire codec"

dependencies {
    api("com.fasterxml.jackson.core:jackson-annotations:2.19.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
