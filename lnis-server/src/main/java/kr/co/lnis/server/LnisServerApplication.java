package kr.co.lnis.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class LnisServerApplication {
    public static void main(String[] args) { SpringApplication.run(LnisServerApplication.class, args); }
}

