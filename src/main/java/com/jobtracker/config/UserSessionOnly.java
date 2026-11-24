package com.jobtracker.config;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Restricts an endpoint to the user's own Applywell session (first-party JWT). Tokens issued by the
 * OAuth authorization server to MCP/GPT clients are rejected, so an AI client can use integrations
 * but cannot replace credentials or connect/disconnect accounts.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("!(authentication instanceof T(org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken))")
public @interface UserSessionOnly {
}
