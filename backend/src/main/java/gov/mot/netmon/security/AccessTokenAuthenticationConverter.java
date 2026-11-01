package gov.mot.netmon.security;

import java.util.UUID;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.user.User;
import gov.mot.netmon.user.UserRepository;

/**
 * Turns a signature-verified JWT into an authenticated principal, rejecting it if the user
 * was deactivated, locked or has had all tokens revoked since it was issued.
 */
@Component
public class AccessTokenAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserRepository users;

    public AccessTokenAuthenticationConverter(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidBearerTokenException("Invalid token subject");
        }
        User user = users.findById(userId).orElseThrow(() -> new InvalidBearerTokenException("Unknown user"));
        Object ver = jwt.getClaim(JwtService.CLAIM_TOKEN_VERSION);
        if (!user.isActive() || !(ver instanceof Number n) || n.intValue() != user.getTokenVersion()) {
            throw new InvalidBearerTokenException("Token revoked");
        }
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getUsername(), user.roleCodes());
        var authorities = user.roleCodes().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r.name())).toList();
        return UsernamePasswordAuthenticationToken.authenticated(principal, jwt.getTokenValue(), authorities);
    }
}
