package org.iotloopsim.model;

/**
 * Channel security cost model (e.g. DTLS for CoAP, TLS for MQTT/HTTP).
 *
 * @param overheadBytes    record header + MAC bytes per message
 * @param deviceJoules     energy spent by a constrained device per protected message (crypto)
 * @param serverMi         extra million instructions per message at the receiving service
 * @param handshakeBytes   bytes exchanged by a full handshake (on attach / after handover)
 * @param handshakeRtts    round trips of the handshake
 */
public record SecurityProfile(String name, int overheadBytes, double deviceJoules, double serverMi, int handshakeBytes, int handshakeRtts) {
    /** No channel security: zero overhead and cost. */
    public static final SecurityProfile NONE = new SecurityProfile("none", 0, 0, 0, 0, 0);
    /** DTLS preset: 29 B per message, 0.2 mJ device crypto, 0.05 MI server cost, 1500 B / 2-RTT handshake. */
    public static final SecurityProfile DTLS = new SecurityProfile("dtls", 29, 0.0002, 0.05, 1500, 2);
    /** TLS preset: 29 B per message, 0.3 mJ device crypto, 0.05 MI server cost, 4000 B / 2-RTT handshake. */
    public static final SecurityProfile TLS = new SecurityProfile("tls", 29, 0.0003, 0.05, 4000, 2);

    /**
     * Returns {@link #NONE} (for {@code none} or {@code off}), {@link #DTLS} or {@link #TLS} by case-insensitive
     * name; throws {@link IllegalArgumentException} otherwise.
     */
    public static SecurityProfile preset(String n) {
        return switch (n.toLowerCase()) {
            case "none", "off" -> NONE;
            case "dtls" -> DTLS;
            case "tls" -> TLS;
            default -> throw new IllegalArgumentException("unknown security profile '" + n + "' (none|dtls|tls)");
        };
    }
}
