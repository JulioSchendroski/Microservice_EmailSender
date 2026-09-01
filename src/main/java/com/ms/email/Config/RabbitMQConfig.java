package com.ms.email.Config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${app.rabbitmq.queue}")
    private String queue;

    @Value("${app.rabbitmq.exchange}")
    private String exchange;

    @Value("${app.rabbitmq.routing-key}")
    private String routingKey;

    @Value("${app.rabbitmq.dlq}")
    private String deadLetterQueue;

    @Value("${app.rabbitmq.dlx}")
    private String deadLetterExchange;

    @Value("${app.rabbitmq.dlq-routing-key}")
    private String deadLetterRoutingKey;

    // ---------- Exchanges ----------

    @Bean
    public DirectExchange emailExchange() {
        return new DirectExchange(exchange, true, false);
    }

    @Bean
    public DirectExchange emailDeadLetterExchange() {
        return new DirectExchange(deadLetterExchange, true, false);
    }

    // ---------- Queues ----------

    @Bean
    public Queue emailQueue() {
        // Mensagens rejeitadas (nack sem requeue) vão para o DLX.
        return QueueBuilder.durable(queue)
                .withArgument("x-dead-letter-exchange", deadLetterExchange)
                .withArgument("x-dead-letter-routing-key", deadLetterRoutingKey)
                .build();
    }

    @Bean
    public Queue emailDeadLetterQueue() {
        return QueueBuilder.durable(deadLetterQueue).build();
    }

    // ---------- Bindings ----------

    @Bean
    public Binding emailBinding() {
        return BindingBuilder.bind(emailQueue()).to(emailExchange()).with(routingKey);
    }

    @Bean
    public Binding emailDeadLetterBinding() {
        return BindingBuilder.bind(emailDeadLetterQueue())
                .to(emailDeadLetterExchange())
                .with(deadLetterRoutingKey);
    }

    // ---------- Converter / Template ----------

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter());
        return rabbitTemplate;
    }
}
