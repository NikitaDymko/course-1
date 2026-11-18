package io.github.mitohondriyaa.inventory.config;

import io.github.mitohondriyaa.inventory.converter.KeycloakRealmRoleConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

@Configuration
public class SecurityConfig {
    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json");
            response.getWriter().write(
                """
                    {
                        "code": "UNAUTHORIZED",
                        "message": "User not authenticated"
                    }
                    """
            );
        };
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("application/json");
            response.getWriter().write("""
                    {
                        "code": "FORBIDDEN",
                        "message": "User not authorized"
                    }
                """);
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        AuthenticationEntryPoint entryPoint,
        AccessDeniedHandler handler
    ) throws Exception {
        return http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(
                    "/swagger-ui/**",
                    "/swagger-api/**",
                    "/swagger-ui.html"
                )
                .permitAll()
                // Изменение остатков — кладовщик или менеджер по запасам
                .requestMatchers(HttpMethod.PUT, "/api/inventory/**")
                .hasAnyRole("STOREKEEPER", "INVENTORY_MANAGER", "MANAGER")
                // Просмотр остатков — кладовщик, продавец, менеджер по запасам
                .requestMatchers(HttpMethod.GET, "/api/inventory/**")
                .hasAnyRole("STOREKEEPER", "SELLER", "INVENTORY_MANAGER", "MANAGER")
                .anyRequest()
                .authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationEntryPoint(entryPoint)
                .accessDeniedHandler(handler)
                .jwt(jwt -> jwt.jwtAuthenticationConverter(new KeycloakRealmRoleConverter())))
            .build();
    }
}
