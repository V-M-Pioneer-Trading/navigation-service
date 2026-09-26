package de.vnm.navigation.introspection.web;

import de.vnm.navigation.config.IntrospectionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Java {@code secured()}: an application with a handler that carries no usable
 * declaration refuses to start, and says which mapping and which handler.
 *
 * <p>Each context here is the real Spring MVC stack plus {@link IntrospectionConfig} — the
 * same configuration production runs — and a controller or two. The center is never called
 * during startup, so the URL only has to be well formed.
 */
class DeclarationAuditTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class,
                    ErrorMvcAutoConfiguration.class))
            .withUserConfiguration(IntrospectionConfig.class)
            .withPropertyValues(
                    "AUTH_INTROSPECTION_URL=http://127.0.0.1:9/auth/v1/introspect",
                    "AUTH_INTROSPECTION_SECRET=audit-test-secret");

    @RestController
    static class FullyDeclared {
        @AllowPublic @GetMapping("/public") String read() { return ""; }
        @RequireSession @GetMapping("/session") String session() { return ""; }
        @RequireScope("universe:refresh") @PostMapping("/refresh") String refresh() { return ""; }
        @IgnoreCredentials @GetMapping("/health") String health() { return ""; }
    }

    @RestController
    static class Undeclared {
        @PostMapping("/probe/undeclared") String undeclared() { return ""; }
    }

    @RestController
    static class UndeclaredRead {
        @GetMapping("/probe/undeclared-read") String read() { return ""; }
    }

    @RestController
    static class PublicMutation {
        @AllowPublic @PostMapping("/probe/public-post") String write() { return ""; }
    }

    @RestController
    static class IgnoringEveryMethod {
        @IgnoreCredentials @RequestMapping("/probe/any-method") String any() { return ""; }
    }

    @RestController
    static class TwoDeclarations {
        @AllowPublic @RequireSession @GetMapping("/probe/two") String two() { return ""; }
    }

    @RestController
    static class ReservedWordAsScope {
        @RequireScope("session") @PostMapping("/probe/reserved") String reserved() { return ""; }
    }

    @Test
    void aFullyDeclaredApplicationStarts() {
        runner.withUserConfiguration(FullyDeclared.class)
              .run(context -> assertThat(context).hasNotFailed().hasSingleBean(DeclarationAudit.class));
    }

    @Test
    void anUndeclaredHandlerRefusesToStart_namingTheMappingAndTheHandler() {
        runner.withUserConfiguration(FullyDeclared.class, Undeclared.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(startupFailure(context))
                  .hasMessageContaining("Refusing to start")
                  .hasMessageContaining("{POST [/probe/undeclared]}")
                  .hasMessageContaining("DeclarationAuditTest$Undeclared#undeclared()")
                  .hasMessageContaining("carries none of @AllowPublic, @RequireSession, @RequireScope or @IgnoreCredentials");
        });
    }

    /** Undeclared is never "none", not even on a GET. */
    @Test
    void anUndeclaredReadRefusesToStartToo() {
        runner.withUserConfiguration(UndeclaredRead.class).run(context ->
                assertThat(startupFailure(context)).hasMessageContaining("{GET [/probe/undeclared-read]}"));
    }

    @Test
    void aPublicMutationRefusesToStart() {
        runner.withUserConfiguration(PublicMutation.class).run(context ->
                assertThat(startupFailure(context))
                        .hasMessageContaining("{POST [/probe/public-post]}")
                        .hasMessageContaining("answers a mutating method but declares no session or scope"));
    }

    /** No method condition means every method, POST included. */
    @Test
    void ignoringCredentialsOnEveryMethodRefusesToStart() {
        runner.withUserConfiguration(IgnoringEveryMethod.class).run(context ->
                assertThat(startupFailure(context))
                        .hasMessageContaining("[/probe/any-method]")
                        .hasMessageContaining("answers a mutating method but declares no session or scope"));
    }

    @Test
    void twoDeclarationsOnOneHandlerRefuseToStart() {
        runner.withUserConfiguration(TwoDeclarations.class).run(context ->
                assertThat(startupFailure(context)).hasMessageContaining("declares 2 requirements"));
    }

    @Test
    void aReservedWordSpelledAsAScopeRefusesToStart() {
        runner.withUserConfiguration(ReservedWordAsScope.class).run(context ->
                assertThat(startupFailure(context))
                        .hasMessageContaining("{POST [/probe/reserved]}")
                        .hasMessageContaining("\"session\" is not a scope"));
    }

    /** Every problem is named in one startup, not one per deploy. */
    @Test
    void everyProblemIsNamedAtOnce() {
        runner.withUserConfiguration(Undeclared.class, PublicMutation.class, TwoDeclarations.class).run(context ->
                assertThat(startupFailure(context))
                        .hasMessageContaining("3 handler mapping(s)")
                        .hasMessageContaining("/probe/undeclared")
                        .hasMessageContaining("/probe/public-post")
                        .hasMessageContaining("/probe/two"));
    }

    // ── configuration ──────────────────────────────────────────────────────────────────

    @Test
    void anEmptyIntrospectionUrlRefusesToStart() {
        runner.withUserConfiguration(FullyDeclared.class)
              .withPropertyValues("AUTH_INTROSPECTION_URL=")
              .run(context -> assertThat(startupFailure(context))
                      .hasMessageContaining("AUTH_INTROSPECTION_URL is required"));
    }

    @Test
    void anEmptyIntrospectionSecretRefusesToStart() {
        runner.withUserConfiguration(FullyDeclared.class)
              .withPropertyValues("AUTH_INTROSPECTION_SECRET=")
              .run(context -> assertThat(startupFailure(context))
                      .hasMessageContaining("AUTH_INTROSPECTION_SECRET is required"));
    }

    /**
     * The Clerk variables the deployment keeps for rollback until meta#80 step 10 are not a
     * way back to local verification: nothing reads them, so they neither satisfy the
     * requirement nor break a correctly configured start.
     */
    @Test
    void theOldClerkVariablesAreIgnored() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class))
                .withUserConfiguration(IntrospectionConfig.class, FullyDeclared.class)
                .withPropertyValues("CLERK_JWT_KEY=-----BEGIN PUBLIC KEY-----garbage", "CLERK_ISSUER=https://x",
                        "clerk.jwt-key=garbage")
                .run(context -> assertThat(startupFailure(context))
                        .hasMessageContaining("AUTH_INTROSPECTION_URL is required"));

        runner.withUserConfiguration(FullyDeclared.class)
              .withPropertyValues("CLERK_JWT_KEY=-----BEGIN PUBLIC KEY-----garbage", "CLERK_ISSUER=https://x")
              .run(context -> assertThat(context).hasNotFailed());
    }

    /** The most specific cause: the audit's own exception, or the settings' one inside a bean-creation failure. */
    private static Throwable startupFailure(AssertableWebApplicationContext context) {
        assertThat(context).hasFailed();
        return NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure());
    }
}
