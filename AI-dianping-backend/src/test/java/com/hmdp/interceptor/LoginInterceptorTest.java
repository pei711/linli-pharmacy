package com.hmdp.interceptor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginInterceptorTest {

    @Test
    void allowsSwaggerAndOpenApiResourcesWithoutAuthentication() {
        assertTrue(LoginInterceptor.isPublicDocumentationPath("/swagger-ui/", ""));
        assertTrue(LoginInterceptor.isPublicDocumentationPath("/swagger-ui/index.html", ""));
        assertTrue(LoginInterceptor.isPublicDocumentationPath("/v3/api-docs", ""));
        assertTrue(LoginInterceptor.isPublicDocumentationPath("/v3/api-docs/pharmacy", ""));
        assertTrue(LoginInterceptor.isPublicDocumentationPath("/app/webjars/swagger-ui.js", "/app"));
    }

    @Test
    void keepsBusinessEndpointsAndLookalikePrefixesProtected() {
        assertFalse(LoginInterceptor.isPublicDocumentationPath("/api/v1/stores", ""));
        assertFalse(LoginInterceptor.isPublicDocumentationPath("/swagger-ui-evil/", ""));
        assertFalse(LoginInterceptor.isPublicDocumentationPath("/application/swagger-ui/", "/app"));
    }
}
