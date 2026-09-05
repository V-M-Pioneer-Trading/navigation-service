package de.vnm.navigation.service;

import java.util.regex.Pattern;

/**
 * Parsing and validation for SpaceTraders symbols.
 *
 * <p>Symbols arrive as path variables and go straight back out as upstream path
 * segments, so every entry point validates through here — previously only the
 * waypoint paths did, and a system lookup accepted anything the router matched.
 * An invalid symbol is a caller error ({@link IllegalArgumentException} → 400),
 * never an upstream one.
 */
public final class Symbols {

    /** Whitespace and URL delimiters have no place in a symbol. */
    private static final Pattern ILLEGAL = Pattern.compile("[\\s/?#%\\\\]");

    private Symbols() {}

    /**
     * System symbol a waypoint belongs to: {@code X1-FQ86-B29} → {@code X1-FQ86}.
     *
     * @throws IllegalArgumentException if the symbol is not of the form {@code SECTOR-SYSTEM-ID}
     */
    public static String systemOf(String waypointSymbol) {
        requireWellFormed(waypointSymbol, "waypoint");
        int lastDash = waypointSymbol.lastIndexOf('-');
        if (lastDash <= 0) {
            throw new IllegalArgumentException(
                    "Invalid waypoint symbol (expected format SECTOR-SYSTEM-ID): " + waypointSymbol);
        }
        return waypointSymbol.substring(0, lastDash);
    }

    /** @throws IllegalArgumentException if the system symbol is blank or malformed */
    public static String requireSystem(String systemSymbol) {
        requireWellFormed(systemSymbol, "system");
        return systemSymbol;
    }

    private static void requireWellFormed(String symbol, String kind) {
        if (symbol == null || symbol.isBlank() || ILLEGAL.matcher(symbol).find()) {
            throw new IllegalArgumentException("Invalid " + kind + " symbol: " + symbol);
        }
    }
}
