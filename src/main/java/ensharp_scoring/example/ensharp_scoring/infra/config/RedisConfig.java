package ensharp_scoring.example.ensharp_scoring.infra.config;

import ensharp_scoring.example.ensharp_scoring.scoring.adapter.in.messaging.ScoringRequestListenerAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

@Configuration
public class RedisConfig {

    private static final String SUBMISSION_QUEUE = "oj:submission:queue";

    @Bean
    public RedisMessageListenerContainer container(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter listenerAdapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listenerAdapter, new PatternTopic(SUBMISSION_QUEUE));
        return container;
    }

    @Bean
    public MessageListenerAdapter listenerAdapter(ScoringRequestListenerAdapter listener) {
        return new MessageListenerAdapter(listener);
    }
}
