package kr.co.lnis.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {
    @Bean RedisTemplate<String, byte[]> binaryRedisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>(); template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer()); template.setValueSerializer(RedisSerializer.byteArray());
        template.afterPropertiesSet(); return template;
    }
}
