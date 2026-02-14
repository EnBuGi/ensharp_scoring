package ensharp_scoring.example.ensharp_scoring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class EnsharpScoringApplicationTests {

	@org.springframework.test.context.bean.override.mockito.MockitoBean
	private ensharp_scoring.example.ensharp_scoring.scoring.service.ScoringService scoringService;

	@org.springframework.test.context.bean.override.mockito.MockitoBean
	private org.springframework.data.redis.connection.RedisConnectionFactory redisConnectionFactory;

	@org.springframework.test.context.bean.override.mockito.MockitoBean
	private org.springframework.data.redis.listener.RedisMessageListenerContainer redisMessageListenerContainer;

	@org.springframework.test.context.bean.override.mockito.MockitoBean
	private org.springframework.data.redis.connection.ReactiveRedisConnectionFactory reactiveRedisConnectionFactory;

	@Test
	void contextLoads() {
	}

}
