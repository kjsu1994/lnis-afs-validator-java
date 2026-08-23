package kr.co.lnis.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
/** LNIS 중앙 REST API, WebSocket 및 Redis 서비스를 시작하는 Spring Boot 진입점이다. */
public class LnisServerApplication {
    public static void main(String[] args) { SpringApplication.run(LnisServerApplication.class, args); }
}
