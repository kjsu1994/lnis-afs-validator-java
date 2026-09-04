package server.central.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 기존 Nginx의 정적 asset 경로와 1시간 cache 정책을 Spring MVC로 대체한다. */
@Configuration
public class StaticWebConfig implements WebMvcConfigurer {
  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/lnis/assets/**")
        .addResourceLocations("classpath:/static/assets/")
        .setCachePeriod(3600);
  }
}
