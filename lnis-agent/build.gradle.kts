plugins { application }

dependencies {
    implementation(project(":lnis-protocol"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.19.2")
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("com.fazecast:jSerialComm:2.11.4")
    implementation("org.slf4j:slf4j-api:2.0.17")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application { mainClass = "kr.co.lnis.agent.LnisAgentApplication" }

distributions {
    main {
        contents {
            from(rootProject.file("native/bin/win-x64")) { into("native") }
            from("conf") { into("conf") }
            from("launcher")
            from("runtime-support") { into("runtime") }
            from("service") { into("service") }
        }
    }
}

tasks.jar {
    manifest { attributes["Main-Class"] = application.mainClass.get() }
}
