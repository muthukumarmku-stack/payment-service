package com.learning.paymentservice.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Title and description shown at the top of the Swagger UI. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI paymentServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Payment Management API")
                .version("v1")
                .description("Create payments, read them by id, change their status, and browse payment "
                        + "history. Errors are RFC 9457 problem details with a stable `code` field."));
    }
}
