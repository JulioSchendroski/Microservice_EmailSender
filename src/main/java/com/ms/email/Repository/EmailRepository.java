package com.ms.email.Repository;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import com.ms.email.Model.EmailModel;

/**
 * Repositório JPA para persistência e consulta de e-mails.
 */
public interface EmailRepository extends JpaRepository<EmailModel, UUID>{
    
}
