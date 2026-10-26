package com.edac;

import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {
    @Bean @Order(1) @Profile("local")
    SecurityFilterChain console(HttpSecurity http) throws Exception {
        return http.securityMatcher(PathRequest.toH2Console())
            .authorizeHttpRequests(a -> a.anyRequest().permitAll())
            .csrf(c -> c.disable())
            .headers(h -> h.frameOptions(f -> f.sameOrigin())).build();
    }

    @Bean @Order(2)
    SecurityFilterChain api(HttpSecurity http, Environment environment) throws Exception {
        boolean local = environment.acceptsProfiles(Profiles.of("local"));
        http.cors(Customizer.withDefaults())
            .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(a -> {
                a.requestMatchers("/h2-console/**").denyAll();
                a.requestMatchers("/error").permitAll();
                a.requestMatchers(HttpMethod.GET,"/sample-readable-fields.csv").permitAll();
                a.requestMatchers(HttpMethod.GET, "/reports", "/reports/user-profile-matrix").permitAll();
                a.requestMatchers(HttpMethod.GET, "/", "/index.html", "/overview", "/connections", "/tables", "/mapping", "/generation", "/triggers", "/snapshot", "/snapshot/**", "/*.js", "/*.css", "/media/**", "/sample-tables.csv", "/sample-screens.csv", "/favicon.ico").permitAll();
                if (local) a.requestMatchers("/api/**").permitAll();
                else a.requestMatchers("/api/**").authenticated();
                a.anyRequest().denyAll();
            });
        if (!local) http.httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    UserDetailsService users(Environment environment, @Value("${edac.admin-password}") String password) {
        if (environment.acceptsProfiles(Profiles.of("local"))) return new InMemoryUserDetailsManager();
        if (password.length() < 16) throw new IllegalStateException("Set EDAC_ADMIN_PASSWORD to at least 16 characters outside the local profile.");
        return new InMemoryUserDetailsManager(User.withUsername("edac-admin")
            .password("{bcrypt}"+new BCryptPasswordEncoder().encode(password)).roles("ADMIN").build());
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${edac.allowed-origins}") String origins) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).toList());
        c.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        c.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "Authorization"));
        c.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", c);
        return source;
    }
}
