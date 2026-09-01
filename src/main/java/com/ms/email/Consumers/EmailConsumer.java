package com.ms.email.Consumers;

import com.ms.email.Dto.EmailDto;
import com.ms.email.Model.EmailModel;
import com.ms.email.Service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
public class EmailConsumer {

    private static final Logger log = LoggerFactory.getLogger(EmailConsumer.class);

    private final EmailService emailService;

    public EmailConsumer(EmailService emailService) {
        this.emailService = emailService;
    }

    /**
     * Consome mensagens da fila de e-mail.
     *
     * Se o envio falhar, {@link EmailService#createAndSend} lança
     * EmailSendException. Como o listener não captura, o container faz nack
     * da mensagem. Após esgotar as tentativas de retry configuradas em
     * application.properties, a mensagem é encaminhada para a DLQ via DLX.
     */
    @RabbitListener(queues = "${app.rabbitmq.queue}")
    public void listen(@Payload EmailDto emailDto) {
        log.info("Mensagem recebida para envio de e-mail para {}", emailDto.getEmailTo());
        EmailModel emailModel = emailService.createAndSend(emailDto);
        log.info("E-mail {} processado com status {}",
                emailModel.getEmailId(), emailModel.getStatusEmail());
    }
}
