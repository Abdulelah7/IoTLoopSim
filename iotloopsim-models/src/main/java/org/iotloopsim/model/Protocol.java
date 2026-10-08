package org.iotloopsim.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Application/transport protocol overhead and reliability model.
 *
 * @param headerBytes   protocol + transport + network header bytes added to each message
 * @param reliable      whether losses are recovered by retransmission
 * @param ackBytes      bytes of the acknowledgement (0 if none)
 * @param maxRetries    maximum retransmissions for reliable protocols
 * @param timeoutNs     initial retransmission timeout (doubled after each retry)
 */
public record Protocol(String name, int headerBytes, boolean reliable, int ackBytes, int maxRetries, long timeoutNs) {
    private static final Map<String, Protocol> PRESETS = new LinkedHashMap<>();
    static {
        // MQTT over TCP/IP: 2 B fixed header + ~20 B topic + 40 B TCP/IP
        add(new Protocol("mqtt-qos0", 62, false, 0, 0, 0));
        add(new Protocol("mqtt-qos1", 64, true, 44, 4, 1_000_000_000L));
        add(new Protocol("mqtt-qos2", 64, true, 132, 4, 1_000_000_000L)); // PUBREC+PUBREL+PUBCOMP
        // CoAP over UDP/IP: 4 B header + ~8 B options + 28 B UDP/IP; CON uses ACK_TIMEOUT 2 s, MAX_RETRANSMIT 4
        add(new Protocol("coap-non", 40, false, 0, 0, 0));
        add(new Protocol("coap-con", 40, true, 32, 4, 2_000_000_000L));
        add(new Protocol("http", 240, true, 40, 5, 1_000_000_000L));
        add(new Protocol("amqp", 56, true, 48, 4, 1_000_000_000L));
        add(new Protocol("udp", 28, false, 0, 0, 0));
        add(new Protocol("none", 0, false, 0, 0, 0));    // payload sent as-is, e.g. LoRaWAN FRMPayload (MAC header is the technology frameHeader)
        add(new Protocol("tcp", 40, true, 40, 5, 1_000_000_000L));
    }

    private static void add(Protocol p) { PRESETS.put(p.name, p); }

    /**
     * Returns a built-in protocol by case-insensitive name ({@code mqtt} maps to {@code mqtt-qos1}, {@code coap}
     * to {@code coap-con}); throws {@link IllegalArgumentException} for unknown names.
     */
    public static Protocol preset(String name) {
        String n = name.toLowerCase();
        if (n.equals("mqtt")) n = "mqtt-qos1";
        if (n.equals("coap")) n = "coap-con";
        Protocol p = PRESETS.get(n);
        if (p == null) throw new IllegalArgumentException("unknown protocol '" + name + "'; known: " + PRESETS.keySet());
        return p;
    }

    /** Returns the built-in protocol table keyed by name (live map, do not modify). */
    public static Map<String, Protocol> presets() { return PRESETS; }
}
