package com.commercehub.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = {"commercehub.events.enabled", "commercehub.events.workers-enabled"},
    havingValue = "true")
public class KafkaConfiguration {
  @Bean
  NewTopic orderTopic() {
    return TopicBuilder.name(OrderEvent.TOPIC).partitions(3).replicas(1)
        .config("max.message.bytes", "65536").build();
  }

  @Bean
  DefaultErrorHandler durableConsumerErrors() {
    // Database outages must never be recovered by discarding/committing a failed record.
    return new DefaultErrorHandler(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
  }
}
