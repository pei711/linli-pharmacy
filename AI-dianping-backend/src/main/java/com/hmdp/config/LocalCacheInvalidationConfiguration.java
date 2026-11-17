package com.hmdp.config;

import com.hmdp.utils.CacheClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

@Configuration
public class LocalCacheInvalidationConfiguration {
    @Bean
    public RedisMessageListenerContainer localCacheInvalidationListenerContainer(
            RedisConnectionFactory connectionFactory, CacheClient cacheClient) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) ->
                        cacheClient.invalidateLocal(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(CacheClient.LOCAL_CACHE_INVALIDATION_CHANNEL));
        return container;
    }
}
