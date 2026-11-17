package com.hmdp.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Neighborhood Pharmacy API",
        version = "v1",
        description = "Community pharmacy catalog, batch inventory, reservations, inbound review, and operations APIs."
))
public class OpenApiConfiguration {
}
