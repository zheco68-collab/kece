package com.kece.common.security;

import com.kece.common.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/** JWT 生成与解析工具，使用 HS256 签名。 */
@Component
public class JwtUtil {

    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_TOKEN_VERSION = "tv";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtUtil(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /** 生成访问令牌，携带 userId（sub）、username、roles 与默认版本号 0。 */
    public String generateAccessToken(Long userId, String username, Collection<String> roles) {
        return generateAccessToken(userId, username, roles, 0);
    }

    /** 生成访问令牌，携带 userId（sub）、username、roles 与 tokenVersion。 */
    public String generateAccessToken(
            Long userId, String username, Collection<String> roles, int tokenVersion) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_ROLES, roles)
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .claim(CLAIM_TOKEN_VERSION, tokenVersion)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + properties.getExpiration()))
                .signWith(key)
                .compact();
    }

    /** 生成刷新令牌，携带 userId、username 与默认版本号 0。 */
    public String generateRefreshToken(Long userId, String username) {
        return generateRefreshToken(userId, username, 0);
    }

    /** 生成刷新令牌，携带 userId、username 与 tokenVersion。 */
    public String generateRefreshToken(Long userId, String username, int tokenVersion) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .claim(CLAIM_TOKEN_VERSION, tokenVersion)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + properties.getRefreshExpiration()))
                .signWith(key)
                .compact();
    }

    /** 解析并校验签名与有效期，返回 claims；失败抛出 {@link JwtException}。 */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    /** 解析访问令牌为认证用户；令牌类型不符时抛异常。 */
    public AuthUser parseToAuthUser(String token) {
        Claims claims = parse(token);
        if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new JwtException("非访问令牌");
        }
        Long id = Long.valueOf(claims.getSubject());
        String username = claims.get(CLAIM_USERNAME, String.class);
        Set<String> roles = toRoleSet(claims.get(CLAIM_ROLES, List.class));
        int tokenVersion = extractTokenVersion(claims);
        return new AuthUser(id, username, roles, tokenVersion);
    }

    /** 从 JWT Claims 中提取令牌版本号（缺省按 0 处理）。 */
    public int extractTokenVersion(Claims claims) {
        Number tv = claims.get(CLAIM_TOKEN_VERSION, Number.class);
        return tv != null ? tv.intValue() : 0;
    }

    /** 判断令牌是否为刷新令牌（不校验过期，仅判断类型）。 */
    public boolean isRefreshToken(String token) {
        return TYPE_REFRESH.equals(parse(token).get(CLAIM_TYPE, String.class));
    }

    @SuppressWarnings("unchecked")
    private Set<String> toRoleSet(List<?> rawRoles) {
        if (rawRoles == null) {
            return Set.of();
        }
        return (Set<String>) rawRoles.stream().map(String::valueOf).collect(Collectors.toSet());
    }
}
