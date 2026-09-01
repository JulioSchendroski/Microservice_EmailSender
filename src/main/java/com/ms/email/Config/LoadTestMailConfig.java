package com.ms.email.Config;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.InputStream;

/**
 * Mock de SMTP ativado APENAS no profile "loadtest".
 *
 * Substitui o {@link JavaMailSender} real por uma implementação que NÃO envia
 * e-mail — apenas simula a latência de um servidor SMTP. Serve para rodar
 * testes de carga (ex.: k6) contra a aplicação sem disparar e-mails reais,
 * mesmo que existam credenciais SMTP configuradas.
 *
 * Latência configurável via `loadtest.smtp.latency-ms` (default 150ms).
 */
@Configuration
@Profile("loadtest")
public class LoadTestMailConfig {

    private static final Logger log = LoggerFactory.getLogger(LoadTestMailConfig.class);

    @Bean
    @Primary
    public JavaMailSender mockMailSender(
            @Value("${loadtest.smtp.latency-ms:150}") long latencyMs) {
        log.warn("[LOADTEST] SMTP MOCKADO ativo — nenhum e-mail real será enviado "
                + "(latência simulada: {} ms)", latencyMs);
        return new LatencySimulatingMailSender(latencyMs);
    }

    static class LatencySimulatingMailSender implements JavaMailSender {
        private final long latencyMs;

        LatencySimulatingMailSender(long latencyMs) {
            this.latencyMs = latencyMs;
        }

        private void simulate() {
            if (latencyMs <= 0) return;
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void send(SimpleMailMessage simpleMessage) {
            simulate();
        }

        @Override
        public void send(SimpleMailMessage... simpleMessages) {
            for (SimpleMailMessage m : simpleMessages) simulate();
        }

        @Override
        public MimeMessage createMimeMessage() {
            return new MimeMessage((jakarta.mail.Session) null);
        }

        @Override
        public MimeMessage createMimeMessage(InputStream contentStream) {
            return new MimeMessage((jakarta.mail.Session) null);
        }

        @Override
        public void send(MimeMessage mimeMessage) {
            simulate();
        }

        @Override
        public void send(MimeMessage... mimeMessages) {
            for (MimeMessage m : mimeMessages) simulate();
        }
    }
}
