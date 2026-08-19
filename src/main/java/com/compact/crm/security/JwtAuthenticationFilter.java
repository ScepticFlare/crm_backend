package com.compact.crm.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;

    // /api/auth/login is permitAll() in SecurityConfig, but that only
    // decides authorization AFTER this filter has already run - it does not
    // stop this filter from executing first. A stale/expired/malformed
    // Bearer token left in the browser (e.g. from a previous session) would
    // otherwise still reach the parsing logic below on every login attempt.
    // Skipping the filter entirely for this path is the actual fix; the
    // try/catch below is defense-in-depth for every other endpoint.
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return "/api/auth/login".equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        // Step 1
        final String authHeader = request.getHeader("Authorization");

        // Step 2
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Step 3
        String jwt = authHeader.substring(7);

        // A stale/expired/malformed/wrong-signature token must never crash
        // the request pipeline with an uncaught JwtException (500). Treat
        // any parsing failure as "not authenticated" and let the normal
        // authorization rules (anyRequest().authenticated()) reject the
        // request with 401/403 instead.
        try {

            // Step 4
            String email = jwtService.extractUsername(jwt);

            // Step 5
            if (email != null &&
                    SecurityContextHolder.getContext().getAuthentication() == null) {

                UserDetails user =
                        userDetailsService.loadUserByUsername(email);

                // Step 6
                if (jwtService.isTokenValid(jwt, user)) {

                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(
                                    user,
                                    null,
                                    user.getAuthorities()
                            );

                    authToken.setDetails(
                            new WebAuthenticationDetailsSource()
                                    .buildDetails(request)
                    );

                    SecurityContextHolder
                            .getContext()
                            .setAuthentication(authToken);
                }
            }

        } catch (JwtException | UsernameNotFoundException | IllegalArgumentException ex) {

            log.debug("Ignoring invalid Bearer token on {}: {}", request.getServletPath(), ex.getMessage());
            SecurityContextHolder.clearContext();
        }

        // Step 7
        filterChain.doFilter(request, response);
    }
}