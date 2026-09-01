package com.ms.email.Service;

import com.ms.email.Dto.EmailDto;
import com.ms.email.Enum.StatusEmail;
import com.ms.email.Model.EmailModel;
import com.ms.email.Repository.EmailRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final EmailRepository emailRepository;
    private final JavaMailSender emailSender;

    public EmailService(EmailRepository emailRepository, JavaMailSender emailSender) {
        this.emailRepository = emailRepository;
        this.emailSender = emailSender;
    }

    /**
     * Converte o DTO em entidade e persiste com status PENDING.
     * Usado tanto pelo fluxo síncrono quanto pelo assíncrono.
     */
    @Transactional
    public EmailModel createPending(EmailDto emailDto) {
        EmailModel emailModel = new EmailModel();
        BeanUtils.copyProperties(emailDto, emailModel);
        emailModel.setStatusEmail(StatusEmail.PENDING);
        return emailRepository.save(emailModel);
    }

    /**
     * Envia o e-mail e atualiza o status.
     *
     * Diferente da versão antiga, uma falha de envio é propagada como
     * {@link EmailSendException}. Isso permite que:
     * - o fluxo síncrono retorne um status HTTP de erro adequado;
     * - o consumer assíncrono rejeite a mensagem e acione a DLQ/retry.
     *
     * O status ERROR ainda é persistido para fins de auditoria antes de propagar.
     */
    @Transactional
    public EmailModel sendEmail(EmailModel emailModel) {
        emailModel.setSendDataTime(LocalDateTime.now());
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(emailModel.getEmailFrom());
            message.setTo(emailModel.getEmailTo());
            message.setSubject(emailModel.getSubject());
            message.setText(emailModel.getText());
            emailSender.send(message);

            emailModel.setStatusEmail(StatusEmail.SENT);
            EmailModel saved = emailRepository.save(emailModel);
            log.info("E-mail {} enviado com sucesso para {}", saved.getEmailId(), saved.getEmailTo());
            return saved;
        } catch (MailException e) {
            emailModel.setStatusEmail(StatusEmail.ERROR);
            emailRepository.save(emailModel);
            log.error("Falha ao enviar e-mail para {}: {}", emailModel.getEmailTo(), e.getMessage());
            throw new EmailSendException("Falha ao enviar e-mail para " + emailModel.getEmailTo(), e);
        }
    }

    /**
     * Cria (PENDING) e envia em um único fluxo síncrono.
     */
    @Transactional
    public EmailModel createAndSend(EmailDto emailDto) {
        EmailModel emailModel = new EmailModel();
        BeanUtils.copyProperties(emailDto, emailModel);
        return sendEmail(emailModel);
    }

    public Page<EmailModel> findAll(Pageable pageable) {
        return emailRepository.findAll(pageable);
    }

    public Optional<EmailModel> findById(UUID emailId) {
        return emailRepository.findById(emailId);
    }
}
