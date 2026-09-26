/**
 * navigation-service's client for auth-service's {@code POST /auth/v1/introspect}
 * (auth-design.md decision 21, meta#80 step 7).
 *
 * <p>navigation-service no longer verifies a Clerk token. It sends the bytes it received
 * to the center, gets back {@code {active, sub, scope, exp, kind}}, and decides only what
 * its own route needs. The whole policy is the 37 calling-service cases of
 * {@code meta/fixtures/introspection.json}, vendored into
 * {@code src/test/resources/introspection/} and driven by
 * {@code IntrospectionConformanceTest}.
 *
 * <p>Three layers, each usable without the next, mirroring agent-service's Go client and
 * the family's TypeScript package:
 *
 * <ul>
 *   <li><b>The rules</b> — {@link de.vnm.navigation.introspection.AccessPolicy},
 *       {@link de.vnm.navigation.introspection.Requirement},
 *       {@link de.vnm.navigation.introspection.Bearer},
 *       {@link de.vnm.navigation.introspection.SafeMethods} and
 *       {@link de.vnm.navigation.introspection.Rejection}. Pure; no HTTP anywhere near
 *       them.</li>
 *   <li><b>The center</b> — {@link de.vnm.navigation.introspection.IntrospectionClient}
 *       (the one HTTP call), {@link de.vnm.navigation.introspection.CenterAnswerParser}
 *       (the strict reading of its answer) and
 *       {@link de.vnm.navigation.introspection.IntrospectionSettings} (startup
 *       configuration).</li>
 *   <li><b>The Spring MVC adapter</b> — {@link de.vnm.navigation.introspection.web}: the
 *       four declaration annotations, the {@code HandlerInterceptor} that enforces them,
 *       and the startup audit that refuses to run an undeclared handler.</li>
 * </ul>
 *
 * <p>The first two layers import nothing from the rest of the service; the adapter is the
 * one place that translates an {@link de.vnm.navigation.introspection.Identity} into the
 * service's own {@code auth.Session}. Nothing here parses, decodes or logs a token, and
 * nothing logs the caller secret.
 */
package de.vnm.navigation.introspection;
