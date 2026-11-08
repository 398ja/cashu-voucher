package xyz.tcheeric.cashu.voucher.nostr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import nostr.event.BaseTag;
import nostr.event.impl.GenericEvent;
import nostr.event.tag.GenericTag;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * One NIP-01 publish against one relay: open a WebSocket, send {@code ["EVENT", event]}, wait for
 * {@code ["OK", id, accepted, message]}, close the socket.
 *
 * <p>The returned future completes {@code true} only on {@code OK} with {@code accepted=true} for
 * this event's id, {@code false} on {@code OK} with {@code accepted=false}, and exceptionally on a
 * connection failure, socket error or close, or when {@code timeoutMs} passes without an OK.
 */
@Slf4j
final class RelayPublish implements WebSocket.Listener {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final String relayUrl;
    private final String eventId;
    private final String message;
    private final CompletableFuture<Boolean> result = new CompletableFuture<>();
    private final StringBuilder partial = new StringBuilder();

    private RelayPublish(String relayUrl, String eventId, String message) {
        this.relayUrl = relayUrl;
        this.eventId = eventId;
        this.message = message;
    }

    static CompletableFuture<Boolean> run(String relayUrl, GenericEvent event, long timeoutMs) {
        RelayPublish p;
        try {
            if (event.getId() == null || event.getSignature() == null) {
                throw new VoucherNostrException("refusing to publish an event without id and signature");
            }
            p = new RelayPublish(relayUrl, event.getId(), eventMessage(event));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        CompletableFuture<WebSocket> ws = HTTP.newWebSocketBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, timeoutMs)))
                .buildAsync(URI.create(relayUrl), p);
        ws.whenComplete((socket, error) -> {
            if (error != null) {
                p.result.completeExceptionally(new VoucherNostrException("cannot connect to relay " + relayUrl, error));
            }
        });
        p.result.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        p.result.whenComplete((r, error) -> ws.thenAccept(RelayPublish::close));
        return p.result;
    }

    static String eventMessage(GenericEvent e) {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", e.getId()).put("pubkey", e.getPubKey().toString()).put("created_at", e.getCreatedAt())
                .put("kind", e.getKind());
        ArrayNode tags = n.putArray("tags");
        if (e.getTags() != null) {
            for (BaseTag t : e.getTags()) {
                ArrayNode a = tags.addArray().add(t.getCode());
                if (t instanceof GenericTag g && g.getParams() != null) {
                    g.getParams().forEach(a::add);
                }
            }
        }
        n.put("content", e.getContent() == null ? "" : e.getContent()).put("sig", e.getSignature().toString());
        return JSON.createArrayNode().add("EVENT").add(n).toString();
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.sendText(message, true);
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String text = partial.toString();
            partial.setLength(0);
            try {
                JsonNode node = JSON.readTree(text);
                if (node != null && node.isArray() && node.size() >= 3 && "OK".equals(node.get(0).asText())
                        && eventId.equalsIgnoreCase(node.get(1).asText())) {
                    boolean accepted = node.get(2).isBoolean() && node.get(2).booleanValue();
                    if (!accepted) {
                        log.warn("Relay {} rejected event {}: {}", relayUrl, eventId,
                                node.size() > 3 ? node.get(3).asText() : "");
                    }
                    result.complete(accepted);
                }
            } catch (Exception e) {
                log.debug("Ignoring unreadable relay message from {}: {}", relayUrl, e.getMessage());
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        result.completeExceptionally(new VoucherNostrException(
                "relay " + relayUrl + " closed the connection before OK: " + statusCode));
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        result.completeExceptionally(new VoucherNostrException("relay " + relayUrl + " errored", error));
    }

    private static void close(WebSocket socket) {
        try {
            if (!socket.isOutputClosed()) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "").exceptionally(x -> {
                    socket.abort();
                    return null;
                });
            }
        } catch (RuntimeException e) {
            socket.abort();
        }
    }
}
