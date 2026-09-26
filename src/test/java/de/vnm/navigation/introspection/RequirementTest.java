package de.vnm.navigation.introspection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequirementTest {

    private static final Identity CARRYING_FLEET_CONTROL =
            new Identity("user_x", "operator", List.of("fleet:control"));

    @Test
    void spellsItselfTheWayTheFixtureDoes() {
        assertThat(Requirement.none()).hasToString("none");
        assertThat(Requirement.session()).hasToString("session");
        assertThat(Requirement.scope("universe:refresh")).hasToString("universe:refresh");
    }

    @Test
    void scopeMatchingIsExactMembership() {
        assertThat(Requirement.scope("fleet:control").admits(CARRYING_FLEET_CONTROL)).isTrue();

        Identity near = new Identity("user_x", "operator", List.of("fleet:control:read", "fleet", "FLEET:CONTROL"));
        assertThat(Requirement.scope("fleet:control").admits(near)).isFalse();
        assertThat(Requirement.scope("fleet").admits(CARRYING_FLEET_CONTROL)).isFalse();
    }

    @Test
    void sessionAndNoneAdmitAScopelessSession() {
        Identity scopeless = new Identity("user_x", "operator", List.of());
        assertThat(Requirement.session().admits(scopeless)).isTrue();
        assertThat(Requirement.none().admits(scopeless)).isTrue();
        assertThat(Requirement.scope("fleet:control").admits(scopeless)).isFalse();
    }

    /**
     * A literal no token could ever carry would be a route nobody can call, and a reserved
     * word spelled as a scope reads as a demand while producing its opposite. Both are
     * refused where they are written, so the startup audit names the handler.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "fleet control", " fleet:control", "fleet:control\t",
            "none", "session", "ignore-credentials"})
    void refusesALiteralThatIsNotOneScope(String literal) {
        assertThatThrownBy(() -> Requirement.scope(literal)).isInstanceOf(IllegalArgumentException.class);
    }
}
