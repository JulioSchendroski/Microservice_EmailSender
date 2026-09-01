package com.ms.email.Controller;

import com.ms.email.Dto.EmailDto;
import com.ms.email.Model.EmailModel;
import com.ms.email.Producers.EmailProducer;
import com.ms.email.Service.EmailService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.UUID;

@RestController
public class EmailController {

    private final EmailService emailService;
    private final EmailProducer emailProducer;

    public EmailController(EmailService emailService, EmailProducer emailProducer) {
        this.emailService = emailService;
        this.emailProducer = emailProducer;
    }

    /**
     * Envio SÍNCRONO via HTTP.
     * O e-mail é enviado na hora; a resposta reflete o resultado real.
     * Se o envio falhar, o GlobalExceptionHandler retorna 502.
     */
    @PostMapping("/sending-email")
    public ResponseEntity<EmailModel> sendingEmail(@RequestBody @Valid EmailDto emailDto) {
        EmailModel emailModel = emailService.createAndSend(emailDto);
        return new ResponseEntity<>(emailModel, HttpStatus.CREATED);
    }

    /**
     * Envio ASSÍNCRONO via RabbitMQ.
     * A solicitação é apenas enfileirada e processada por um consumer.
     * Retorna 202 Accepted imediatamente.
     */
    @PostMapping("/sending-email/async")
    public ResponseEntity<Void> sendingEmailAsync(@RequestBody @Valid EmailDto emailDto) {
        emailProducer.publish(emailDto);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/emails")
    public ResponseEntity<Page<EmailModel>> getAllEmails(
            @PageableDefault(page = 0, size = 5, sort = "emailId", direction = Sort.Direction.DESC) Pageable pageable) {
        return new ResponseEntity<>(emailService.findAll(pageable), HttpStatus.OK);
    }

    @GetMapping("/emails/{emailId}")
    public ResponseEntity<Object> getOneEmail(@PathVariable(value = "emailId") UUID emailId) {
        Optional<EmailModel> emailModelOptional = emailService.findById(emailId);
        return emailModelOptional
                .<ResponseEntity<Object>>map(model -> ResponseEntity.status(HttpStatus.OK).body(model))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body("Email not found."));
    }
}
