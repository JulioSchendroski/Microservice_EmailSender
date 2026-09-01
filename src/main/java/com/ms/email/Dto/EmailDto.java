package com.ms.email.Dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Payload de requisição para envio de e-mail (síncrono e assíncrono).
 */
@Data
public class EmailDto {
    @NotBlank
    private String ownerRef;

    @NotBlank
    @Email
    private String emailFrom;

    @NotBlank
    @Email
    private String emailTo;

    @NotBlank
    private String subject;
    
    @NotBlank
    private String text;

}
