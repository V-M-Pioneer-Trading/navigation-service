/**
 * The Spring MVC adapter: how a declaration is written, bound and enforced.
 *
 * <p>A handler declares with exactly one annotation, mapping 1:1 onto the family's
 * vocabulary (ts-introspection-client, agent-service):
 *
 * <table>
 *   <caption>Declarations</caption>
 *   <tr><th>Annotation</th><th>Family</th><th>{@code Authorization}</th><th>Center</th>
 *       <th>Mutating methods</th></tr>
 *   <tr><td>{@link de.vnm.navigation.introspection.web.IgnoreCredentials}</td>
 *       <td>{@code ignoreCredentials()}</td><td>never read</td><td>never called</td>
 *       <td>refused at startup; 500 at request time</td></tr>
 *   <tr><td>{@link de.vnm.navigation.introspection.web.AllowPublic}</td>
 *       <td>{@code allowPublic()}</td><td>optional; verified if presented</td>
 *       <td>called when a bearer is presented</td><td>refused at startup; 500</td></tr>
 *   <tr><td>{@link de.vnm.navigation.introspection.web.RequireSession}</td>
 *       <td>{@code requireSession()}</td><td>required</td><td>called</td><td>enforced</td></tr>
 *   <tr><td>{@link de.vnm.navigation.introspection.web.RequireScope}</td>
 *       <td>{@code requireScope(scope)}</td><td>required, must carry the scope</td>
 *       <td>called</td><td>enforced</td></tr>
 * </table>
 *
 * <p>{@link de.vnm.navigation.introspection.web.IntrospectionInterceptor} enforces them on
 * every dispatch; {@link de.vnm.navigation.introspection.web.DeclarationAudit} is the
 * family's {@code secured()} and refuses to start the application while any handler lacks
 * one. {@link de.vnm.navigation.introspection.web.Declarations} is the single place a
 * handler's declaration is read, including the few framework-owned handlers that cannot
 * carry an annotation.
 */
package de.vnm.navigation.introspection.web;
