package com.ms.email;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ponto de entrada do microserviço de envio de e-mails.
 * Suporta envio síncrono (HTTP) e assíncrono (RabbitMQ).
 */
@SpringBootApplication
public class EmailApplication {

	public static void main(String[] args) {
		SpringApplication.run(EmailApplication.class, args);
	}

}
