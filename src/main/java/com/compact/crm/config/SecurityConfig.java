package com.compact.crm.config;

import com.compact.crm.security.CustomUserDetailsService;
import com.compact.crm.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomUserDetailsService customUserDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    // Comma-separated allowed frontend origins - see
    // crm.cors.allowed-origins in application.properties. Env-var driven so
    // the production frontend's real Render URL can be added/changed via
    // Render's dashboard without a code change or redeploy; the inline
    // default preserves exactly the origins already allowed today.
    @Value("${crm.cors.allowed-origins}")
    private String[] allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                .csrf(csrf -> csrf.disable())

                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                .authorizeHttpRequests(auth -> auth
                        // Login is unauthenticated by nature (that's how a
                        // token is obtained in the first place). logout is
                        // intentionally NOT under this - it records an
                        // activity entry for "the authenticated caller", so
                        // it must require a valid token like every other
                        // resource (see AuthController.logout /
                        // AuthService.recordLogout).
                        //
                        // /health is the Render cold-start liveness check
                        // (HealthController) - it must stay reachable with
                        // no token so the frontend can wake a sleeping
                        // free-tier backend before the user submits login.
                        .requestMatchers(
                                "/api/auth/login",
                                "/health"
                        ).permitAll()

                        // Public lead enquiry form (website "Request/Enquire"
                        // button + brochure QR codes) - the only
                        // unauthenticated write endpoint in the app. Every
                        // internal/security-sensitive field on the Lead it
                        // creates (status, assignee, source) is set
                        // server-side in PublicLeadService, never trusted
                        // from the request body. GET /industries, /products
                        // and /batteries under the same prefix are read-only
                        // passthroughs so the public form can populate its
                        // dropdowns without a login.
                        .requestMatchers(
                                HttpMethod.POST, "/api/public/leads"
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/public/industries", "/api/public/products", "/api/public/batteries"
                        ).permitAll()

                        // Employee-management endpoints previously allowed
                        // unauthenticated access here; they now require a
                        // valid token like every other resource. Fine-grained
                        // (admin-only vs self-service) checks happen in
                        // EmployeeService via AccessControlService.
                        .anyRequest().authenticated()
                )

                .exceptionHandling(ex -> ex
                        // A request with no token, or an expired / invalid /
                        // malformed one, is stopped here before it reaches any
                        // controller. Spring's default entry point for a
                        // bearer-token setup answers 403, which the frontend
                        // cannot tell apart from a genuine RBAC denial. Answer
                        // 401 instead so "your session is gone, sign in again"
                        // is distinct from "you're signed in but not allowed to
                        // do this" - the latter stays 403 via the unchanged
                        // AccessDeniedHandler / GlobalExceptionHandler.
                        .authenticationEntryPoint(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)
                        )
                )

                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                .authenticationProvider(authenticationProvider())

                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration configuration = new CorsConfiguration();

        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins));

        configuration.setAllowedMethods(List.of(
                "GET",
                "POST",
                "PUT",
                "DELETE",
                "OPTIONS"
        ));

        configuration.setAllowedHeaders(List.of("*"));

        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration("/**", configuration);

        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {

        DaoAuthenticationProvider authProvider =
                new DaoAuthenticationProvider(customUserDetailsService);

        authProvider.setPasswordEncoder(passwordEncoder());

        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authenticationConfiguration)
            throws Exception {

        return authenticationConfiguration.getAuthenticationManager();
    }
}