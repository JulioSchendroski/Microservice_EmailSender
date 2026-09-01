package com.ms.email.Producers;

import com.ms.email.Dto.EmailDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EmailProducer {

    private static final Logger log = LoggerFactory.getLogger(EmailProducer.class);

    private final RabbitTemplate rabbitTemplate;

    @Value("${app.rabbitmq.exchange}")
    private String exchange;

    @Value("${app.rabbitmq.routing-key}")
    private String routingKey;

    public EmailProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Publica a solicitação de envio de e-mail na fila para processamento
     * assíncrono pelo {@code EmailConsumer}.
     */
    public void publish(EmailDto emailDto) {
        rabbitTemplate.convertAndSend(exchange, routingKey, emailDto);
        log.info("Solicitação de e-mail para {} enfileirada", emailDto.getEmailTo());
    }
}
