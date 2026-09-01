package com.ms.email;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class EmailApplicationTests {

	// Dependências de infra externa são mockadas para o contexto subir sem
	// broker/SMTP reais durante o build.
	@MockBean
	private JavaMailSender javaMailSender;

	@MockBean
	private ConnectionFactory connectionFactory;

	@Test
	void contextLoads() {
	}

}
