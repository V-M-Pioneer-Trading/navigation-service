package de.vnm.navigation.client;

/**
 * The request classes st-gateway's shared rate budget schedules on (meta#37).
 *
 * <p>The wire value is what goes out as {@code X-Priority}. Callers declare their own:
 * command-interface (browser) sends {@code interactive}, automation-service (autopilot)
 * sends nothing. Anything that is not exactly {@code interactive} — missing, misspelled,
 * or invented — degrades to {@link #BACKGROUND}, so a malformed header can never jump
 * the queue that keeps the browser responsive.
 */
public enum Priority {

    INTERACTIVE("interactive"),
    BACKGROUND("background");

    private final String wireValue;

    Priority(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    /** Parse a caller's {@code X-Priority} header value; {@code null} included. */
    public static Priority from(String header) {
        return INTERACTIVE.wireValue.equals(header) ? INTERACTIVE : BACKGROUND;
    }
}
