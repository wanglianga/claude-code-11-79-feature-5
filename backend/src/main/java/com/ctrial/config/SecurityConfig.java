package com.ctrial.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Demo-grade security: five role-based logins over HTTP Basic, plus a bootstrap
 * admin endpoint. The role split is what makes the dual-domain story real — a
 * China analyst cannot read cross-border deliveries; a global analyst cannot
 * read raw local identity.
 */
@Configuration
public class SecurityConfig {

    public static final String[] ROLES = {"ADMIN", "GLOBAL_ANALYST", "CN_ANALYST", "AUDITOR", "SPONSOR"};

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService users(PasswordEncoder encoder) {
        String p = encoder.encode("demo123");
        return new InMemoryUserDetailsManager(
                User.withUsername("admin").password(p).roles("ADMIN").build(),
                User.withUsername("global").password(p).roles("GLOBAL_ANALYST", "AUDITOR").build(),
                User.withUsername("china").password(p).roles("CN_ANALYST").build(),
                User.withUsername("auditor").password(p).roles("AUDITOR").build(),
                User.withUsername("sponsor").password(p).roles("SPONSOR").build()
        );
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .headers(h -> h.frameOptions(f -> f.sameOrigin()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/session", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole(ROLES)
                        // only a global analyst (or admin) may compile cross-border deliveries
                        .requestMatchers(HttpMethod.POST, "/api/deliveries/**").hasAnyRole("GLOBAL_ANALYST", "ADMIN")
                        // definition-impact comparison: local evaluation stays with the China side; publishing is global
                        .requestMatchers(HttpMethod.POST, "/api/definition-comparison/evaluate")
                        .hasAnyRole("CN_ANALYST", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/definition-comparison/publish",
                                "/api/definition-comparison/annotations")
                        .hasAnyRole("GLOBAL_ANALYST", "ADMIN", "SPONSOR")
                        // only China analysts / admins may push source-side watermark ops
                        .requestMatchers(HttpMethod.POST, "/api/watermarks/**").hasAnyRole("CN_ANALYST", "ADMIN")
                        // consent + event ingestion restricted to admin in this demo
                        .requestMatchers(HttpMethod.POST, "/api/consents/**", "/api/events/**", "/api/protocols/**",
                                "/api/approvals/**", "/api/rules/**").hasAnyRole("ADMIN", "SPONSOR")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // the SPA shell and its assets are public; the app gates itself on the /api calls
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/favicon.ico",
                                "/vite.svg", "/*.js", "/*.css").permitAll()
                        .requestMatchers("/api/**", "/actuator/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        (request, response, ex) -> {
                            response.setStatus(401);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"error\":\"authentication required\"}");
                        },
                        new AntPathRequestMatcher("/api/**")));
        return http.build();
    }
}
