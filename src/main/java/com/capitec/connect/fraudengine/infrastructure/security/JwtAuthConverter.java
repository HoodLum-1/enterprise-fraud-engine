package com.capitec.connect.fraudengine.infrastructure.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Component
public class JwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<String> roleNames = new LinkedHashSet<>();

        addRoles(jwt.getClaim("realm_access"), roleNames);
        addResourceRoles(jwt.getClaim("resource_access"), roleNames);
        addScopeRoles(jwt.getClaim("scope"), roleNames);
        addScopeRoles(jwt.getClaim("scp"), roleNames);

        var authorities = roleNames.stream()
            .map(JwtAuthConverter::toRoleAuthority)
            .map(SimpleGrantedAuthority::new)
            .toList();

        String principalName = jwt.getClaimAsString("preferred_username");
        if (principalName == null || principalName.isBlank()) {
            principalName = jwt.getSubject();
        }
        return new JwtAuthenticationToken(jwt, authorities, principalName);
    }

    private void addResourceRoles(Object resourceAccess, Set<String> roleNames) {
        if (!(resourceAccess instanceof Map<?, ?> resources)) {
            return;
        }
        for (Object resource : resources.values()) {
            addRoles(resource, roleNames);
        }
    }

    private void addRoles(Object access, Set<String> roleNames) {
        if (!(access instanceof Map<?, ?> accessMap)) {
            return;
        }
        addStringValues(accessMap.get("roles"), roleNames);
    }

    private void addScopeRoles(Object scopes, Set<String> roleNames) {
        if (scopes instanceof String scopeString) {
            for (String scope : scopeString.split("\\s+")) {
                if (!scope.isBlank()) {
                    roleNames.add(scope);
                }
            }
        } else if (scopes instanceof Collection<?> scopeCollection) {
            addStringValues(scopeCollection, roleNames);
        }
    }

    private void addStringValues(Object values, Set<String> roleNames) {
        if (!(values instanceof Collection<?> collection)) {
            return;
        }
        collection.stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .filter(value -> !value.isBlank())
            .forEach(roleNames::add);
    }

    private static String toRoleAuthority(String role) {
        return role.startsWith("ROLE_") ? role : "ROLE_" + role;
    }
}
