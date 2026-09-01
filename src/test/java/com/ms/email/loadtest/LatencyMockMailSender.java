package com.ms.email.loadtest;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JavaMailSender FAKE usado apenas em testes de carga.
 *
 * NUNCA envia e-mail de verdade — apenas simula a latência de um servidor SMTP
 * real (rede + handshake + entrega) dormindo por {@code latencyMillis}.
 *
 * Isso garante dois pontos exigidos:
 *  1. Nenhum e-mail real é disparado, mesmo que existam credenciais SMTP reais
 *     configuradas no ambiente.
 *  2. A comparação sync vs async é realista: o custo que o modo síncrono paga
 *     (bloquear o cliente HTTP durante o envio) fica visível.
 */
public class LatencyMockMailSender implements JavaMailSender {

    private static final Logger log = LoggerFactory.getLogger(LatencyMockMailSender.class);

    private final long latencyMillis;
    private final AtomicLong sentCount = new AtomicLong();

    public LatencyMockMailSender(long latencyMillis) {
        this.latencyMillis = latencyMillis;
    }

    private void simulateSmtpLatency() {
        if (latencyMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(latencyMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public long getSentCount() {
        return sentCount.get();
    }

    @Override
    public void send(SimpleMailMessage simpleMessage) {
        simulateSmtpLatency();
        sentCount.incrementAndGet();
        // Descartado de propósito — nenhum envio real.
    }

    @Override
    public void send(SimpleMailMessage... simpleMessages) {
        for (SimpleMailMessage m : simpleMessages) {
            send(m);
        }
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
        simulateSmtpLatency();
        sentCount.incrementAndGet();
    }

    @Override
    public void send(MimeMessage... mimeMessages) {
        for (MimeMessage m : mimeMessages) {
            send(m);
        }
    }
}
