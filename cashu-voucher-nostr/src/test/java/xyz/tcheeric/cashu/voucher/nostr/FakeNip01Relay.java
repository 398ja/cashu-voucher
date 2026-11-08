package xyz.tcheeric.cashu.voucher.nostr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import nostr.event.BaseTag;
import nostr.event.impl.GenericEvent;
import nostr.event.tag.GenericTag;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A minimal in-process NIP-01 relay over a real WebSocket. It stores events and answers
 * {@code REQ} by applying the filter ({@code ids}, {@code authors}, {@code kinds} and
 * {@code #<tag>} keys), then {@code EOSE}. With {@link #silent} set it never sends EOSE.
 */
final class FakeNip01Relay extends WebSocketServer {

    static final ObjectMapper JSON = new ObjectMapper();

    final List<ObjectNode> stored = new CopyOnWriteArrayList<>();
    final List<JsonNode> received = new CopyOnWriteArrayList<>();
    volatile boolean silent;
    /** When positive, answer each REQ with at most this many matches, newest first (like strfry). */
    volatile int cap;
    final java.util.concurrent.atomic.AtomicInteger reqs = new java.util.concurrent.atomic.AtomicInteger();
    private final CountDownLatch started = new CountDownLatch(1);

    FakeNip01Relay() {
        super(new InetSocketAddress("127.0.0.1", 0));
        setReuseAddr(true);
    }

    String url() {
        return "ws://127.0.0.1:" + getPort();
    }

    FakeNip01Relay begin() throws InterruptedException {
        start();
        if (!started.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("relay did not start");
        }
        return this;
    }

    void store(GenericEvent e) {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", e.getId()).put("pubkey", e.getPubKey().toString()).put("created_at", e.getCreatedAt())
                .put("kind", e.getKind());
        ArrayNode tags = n.putArray("tags");
        for (BaseTag t : e.getTags()) {
            ArrayNode a = tags.addArray().add(t.getCode());
            if (t instanceof GenericTag g && g.getParams() != null) {
                g.getParams().forEach(a::add);
            }
        }
        n.put("content", e.getContent()).put("sig", e.getSignature().toString());
        stored.add(n);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        JsonNode msg;
        try {
            msg = JSON.readTree(message);
        } catch (Exception e) {
            return;
        }
        received.add(msg);
        if (!"REQ".equals(msg.get(0).asText())) {
            return;
        }
        String sub = msg.get(1).asText();
        List<JsonNode> filters = new ArrayList<>();
        for (int i = 2; i < msg.size(); i++) {
            filters.add(msg.get(i));
        }
        if (silent) {
            return;
        }
        reqs.incrementAndGet();
        List<ObjectNode> hits = new ArrayList<>();
        for (ObjectNode e : stored) {
            if (filters.stream().anyMatch(f -> matches(f, e))) {
                hits.add(e);
            }
        }
        if (cap > 0) {
            hits.sort((a, b) -> Long.compare(b.get("created_at").asLong(), a.get("created_at").asLong()));
            if (hits.size() > cap) {
                hits = hits.subList(0, cap);
            }
        }
        for (ObjectNode e : hits) {
            conn.send(JSON.createArrayNode().add("EVENT").add(sub).add(e).toString());
        }
        conn.send(JSON.createArrayNode().add("EOSE").add(sub).toString());
    }

    static boolean matches(JsonNode filter, JsonNode e) {
        for (Iterator<Map.Entry<String, JsonNode>> it = filter.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> f = it.next();
            String key = f.getKey();
            List<String> values = new ArrayList<>();
            f.getValue().forEach(v -> values.add(v.asText()));
            boolean ok = switch (key) {
                case "ids" -> values.contains(e.get("id").asText());
                case "authors" -> values.contains(e.get("pubkey").asText());
                case "kinds" -> values.contains(e.get("kind").asText());
                case "until" -> e.get("created_at").asLong() <= f.getValue().asLong();
                case "since" -> e.get("created_at").asLong() >= f.getValue().asLong();
                default -> {
                    if (!key.startsWith("#")) {
                        yield true;
                    }
                    String tag = key.substring(1);
                    boolean any = false;
                    for (JsonNode t : e.get("tags")) {
                        if (t.size() > 1 && tag.equals(t.get(0).asText()) && values.contains(t.get(1).asText())) {
                            any = true;
                        }
                    }
                    yield any;
                }
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
    }

    @Override
    public void onStart() {
        started.countDown();
    }
}
