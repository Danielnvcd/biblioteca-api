package com.biblioteca.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final SessionInvalidationService sessions;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, SessionInvalidationService sessions) {
        this.tokenProvider = tokenProvider;
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = extractToken(request);
            if (StringUtils.hasText(token) && tokenProvider.validateToken(token)) {
                // Reject 2FA step tokens — those only authorize /verify-2fa, never general access.
                String scope = tokenProvider.getScopeFromToken(token);
                if ("access".equals(scope)) {
                    Integer userId = tokenProvider.getUserIdFromToken(token);
                    // Reject tokens issued before the user's last password change.
                    long issuedAt = tokenProvider.getIssuedAtEpoch(token);
                    long pwdChanged = sessions.passwordChangedEpoch(userId);
                    if (pwdChanged > 0 && issuedAt < pwdChanged) {
                        filterChain.doFilter(request, response);
                        return;
                    }
                    String username = tokenProvider.getUsernameFromToken(token);
                    String role = tokenProvider.getRoleFromToken(token);
                    if (role == null) role = "user";

                    UserPrincipal principal = new UserPrincipal(userId, username, role);
                    final String roleAuthority = "ROLE_" + role.toUpperCase();
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            principal, null, Collections.singletonList(() -> roleAuthority));
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        } catch (Exception ex) {
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7).trim();
        }
        return null;
    }
}
