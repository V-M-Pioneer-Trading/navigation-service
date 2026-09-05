package de.vnm.navigation.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SymbolsTest {

    @Test
    void systemOf_stripsTheWaypointSuffix() {
        assertThat(Symbols.systemOf("X1-FQ86-B29")).isEqualTo("X1-FQ86");
        assertThat(Symbols.systemOf("SECTOR-SYS-A1B")).isEqualTo("SECTOR-SYS");
    }

    @Test
    void systemOf_symbolWithoutSeparator_isRejected() {
        assertThatThrownBy(() -> Symbols.systemOf("NOSEPARATOR"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void systemOf_blankOrNull_isRejected() {
        assertThatThrownBy(() -> Symbols.systemOf(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Symbols.systemOf("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireSystem_acceptsAWellFormedSystemSymbol() {
        assertThat(Symbols.requireSystem("X1-FQ86")).isEqualTo("X1-FQ86");
    }

    /** Symbols go straight back out as upstream path segments; delimiters have no place there. */
    @Test
    void requireSystem_symbolWithPathDelimiters_isRejected() {
        assertThatThrownBy(() -> Symbols.requireSystem("X1-FQ86/waypoints"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Symbols.requireSystem("X1 FQ86"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
