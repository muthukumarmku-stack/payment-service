package com.learning.paymentservice.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Fills {@code @CreatedDate} and {@code @LastModifiedDate} fields.
 *
 * <p>Kept out of the main application class on purpose: slice tests such as
 * {@code @WebMvcTest} have no JPA, and would fail if auditing were enabled there.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
