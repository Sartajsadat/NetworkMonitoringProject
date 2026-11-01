package gov.mot.netmon.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import gov.mot.netmon.config.AppProperties;
import gov.mot.netmon.user.User;

/**
 * Issues and verifies short-lived HS256 access tokens. Tokens carry only the user id, username
 * and token version; roles and active status are re-read from the database on every request so
 * that role changes and deactivation take effect immediately.
 */
@Service
public class JwtService {

    public static final String CLAIM_TOKEN_VERSION = "ver";
    public static final String CLAIM_USERNAME = "usr";

    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final AppProperties.Security props;

    public JwtService(AppProperties appProperties) {
        this.props = appProperties.security();
        SecretKey key = new SecretKeySpec(decodeSecret(props.jwtSecret()), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        this.decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.jwtIssuer()));
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }

    public IssuedToken issueAccessToken(User user) {
        Instant now = Instant.now();
        Instant exp = now.plus(props.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.jwtIssuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(exp)
                .claim(CLAIM_USERNAME, user.getUsername())
                .claim(CLAIM_TOKEN_VERSION, user.getTokenVersion())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, exp);
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    private static byte[] decodeSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET is not set. Generate one with: openssl rand -base64 48");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException e) {
            bytes = secret.getBytes(StandardCharsets.UTF_8);
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes (256 bits)");
        }
        return bytes;
    }
}
