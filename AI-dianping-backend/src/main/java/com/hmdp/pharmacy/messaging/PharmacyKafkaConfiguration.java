package com.hmdp.pharmacy.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "app.pharmacy.seckill.enabled", havingValue = "true")
public class PharmacyKafkaConfiguration {
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> pharmacyKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${app.pharmacy.seckill.requested-topic:pharmacy-seckill-requested}") String topic) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(500L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(5_000L);
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, backOff));
        return factory;
    }

    @Bean
    public NewTopic pharmacySeckillRequestedTopic(
            @Value("${app.pharmacy.seckill.requested-topic:pharmacy-seckill-requested}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic pharmacySeckillDeadLetterTopic(
            @Value("${app.pharmacy.seckill.requested-topic:pharmacy-seckill-requested}") String topic) {
        return TopicBuilder.name(topic + ".DLT").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic pharmacyOrderEventsTopic(
            @Value("${app.pharmacy.seckill.order-topic:pharmacy-order-events}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic pharmacyOrderEventsDeadLetterTopic(
            @Value("${app.pharmacy.seckill.order-topic:pharmacy-order-events}") String topic) {
        return TopicBuilder.name(topic + ".DLT").partitions(3).replicas(1).build();
    }
}
