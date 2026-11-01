package gov.mot.netmon.config;

import java.time.Clock;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import gov.mot.netmon.agent.AgentAuthenticationFilter;
import gov.mot.netmon.agent.AgentRepository;
import gov.mot.netmon.security.AccessTokenAuthenticationConverter;
import gov.mot.netmon.security.JsonSecurityHandlers;
import gov.mot.netmon.security.JwtService;
import gov.mot.netmon.security.RateLimiter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** Endpoints reachable without a token. Everything else requires authentication. */
    private static final String[] PUBLIC = {
        "/api/v1/auth/login",
        "/api/v1/auth/refresh",
        "/api/v1/auth/logout",
        "/api/v1/setup/**",
        "/actuator/health/**",
        "/actuator/info",
        "/v3/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html",
    };

    /**
     * Agent API: separate chain, agent credentials only (never user tokens), no cookies, no CORS.
     * Enrollment is public but requires a one-time enrollment token in the body.
     */
    @Bean
    @Order(1)
    SecurityFilterChain agentFilterChain(HttpSecurity http, AgentRepository agents, ObjectMapper mapper, Clock clock,
            JsonSecurityHandlers handlers) throws Exception {
        http
            .securityMatcher("/api/v1/agent/**")
            .csrf(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .anonymous(a -> { })
            .addFilterBefore(new AgentAuthenticationFilter(agents, mapper, clock), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.POST, "/api/v1/agent/enroll").permitAll()
                .anyRequest().hasRole("AGENT"))
            .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService,
            AccessTokenAuthenticationConverter converter, JsonSecurityHandlers handlers) throws Exception {
        http
            // Stateless bearer-token API. The only cookie (refresh token) is SameSite=Strict, scoped to
            // /api/v1/auth and additionally requires a custom header, so CSRF tokens are not needed.
            .csrf(AbstractHttpConfigurer::disable)
            .cors(c -> { })
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .headers(h -> h
                .frameOptions(f -> f.deny())
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .contentSecurityPolicy(c -> c.policyDirectives(
                    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                    + "img-src 'self' data:; frame-ancestors 'none'; object-src 'none'")))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(PUBLIC).permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o
                .jwt(j -> j.decoder(jwtService.decoder()).jwtAuthenticationConverter(converter))
                .authenticationEntryPoint(handlers)
                .accessDeniedHandler(handlers))
            .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RateLimiter loginRateLimiter(AppProperties props, Clock clock) {
        return new RateLimiter(props.security().loginRateLimitPerMinute(), 60_000, clock);
    }

    /** CORS is off unless CORS_ALLOWED_ORIGINS is set (nginx serves UI and API from one origin). */
    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = props.security().corsAllowedOrigins() == null ? List.of()
                : props.security().corsAllowedOrigins().stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (!origins.isEmpty()) {
            CorsConfiguration cfg = new CorsConfiguration();
            cfg.setAllowedOrigins(origins);
            cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
            cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With", "X-Request-Id"));
            cfg.setExposedHeaders(List.of("X-Request-Id"));
            cfg.setAllowCredentials(true);
            cfg.setMaxAge(3600L);
            source.registerCorsConfiguration("/api/**", cfg);
        }
        return source;
    }
}
