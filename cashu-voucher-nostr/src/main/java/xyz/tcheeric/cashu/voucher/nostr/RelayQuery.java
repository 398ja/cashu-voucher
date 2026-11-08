package xyz.tcheeric.cashu.voucher.nostr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import nostr.base.PublicKey;
import nostr.base.Signature;
import nostr.event.BaseTag;
import nostr.event.impl.GenericEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * One NIP-01 query against one relay: open a WebSocket, send {@code REQ}, collect {@code EVENT}s
 * until {@code EOSE}, send {@code CLOSE}, close the socket.
 *
 * <p>The returned future completes normally only on {@code EOSE}. It completes exceptionally on a
 * connection failure, a socket error or close, a {@code CLOSED} for the subscription, or when
 * {@code timeoutMs} passes without {@code EOSE}. Callers that must not fail open rely on this.
 *
 * <p>Events are returned as received; verifying their NIP-01 id and signature is the caller's job.
 */
@Slf4j
final class RelayQuery implements WebSocket.Listener {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final String relayUrl;
    private final String subscriptionId;
    private final String req;
    private final List<GenericEvent> events = new ArrayList<>();
    private final CompletableFuture<List<GenericEvent>> result = new CompletableFuture<>();
    private final StringBuilder partial = new StringBuilder();

    private RelayQuery(String relayUrl, String subscriptionId, String req) {
        this.relayUrl = relayUrl;
        this.subscriptionId = subscriptionId;
        this.req = req;
    }

    static CompletableFuture<List<GenericEvent>> run(String relayUrl, String subscriptionId,
                                                     Map<String, List<String>> filter, long timeoutMs) {
        RelayQuery q;
        try {
            q = new RelayQuery(relayUrl, subscriptionId, reqMessage(subscriptionId, filter));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        CompletableFuture<WebSocket> ws = HTTP.newWebSocketBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, timeoutMs)))
                .buildAsync(URI.create(relayUrl), q);
        ws.whenComplete((socket, error) -> {
            if (error != null) {
                q.result.completeExceptionally(new VoucherNostrException("cannot connect to relay " + relayUrl, error));
            }
        });
        q.result.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        q.result.whenComplete((r, error) -> ws.thenAccept(socket -> q.finish(socket)));
        return q.result;
    }

    static String reqMessage(String subscriptionId, Map<String, List<String>> filter) {
        ArrayNode msg = JSON.createArrayNode().add("REQ").add(subscriptionId);
        ObjectNode f = msg.addObject();
        for (Map.Entry<String, List<String>> e : filter.entrySet()) {
            if ("limit".equals(e.getKey())) {
                throw new IllegalArgumentException("limit is not allowed");
            }
            if ("until".equals(e.getKey()) || "since".equals(e.getKey())) {
                if (e.getValue().size() != 1) {
                    throw new IllegalArgumentException(e.getKey() + " takes exactly one value");
                }
                f.put(e.getKey(), Long.parseLong(e.getValue().get(0)));
                continue;
            }
            ArrayNode values = f.putArray(e.getKey());
            for (String v : e.getValue()) {
                if ("kinds".equals(e.getKey())) {
                    values.add(Integer.parseInt(v));
                } else {
                    values.add(v);
                }
            }
        }
        return msg.toString();
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.sendText(req, true);
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String message = partial.toString();
            partial.setLength(0);
            try {
                handle(message);
            } catch (RuntimeException e) {
                log.debug("Ignoring unreadable relay message from {}: {}", relayUrl, e.getMessage());
            }
        }
        webSocket.request(1);
        return null;
    }

    private void handle(String message) {
        JsonNode node;
        try {
            node = JSON.readTree(message);
        } catch (Exception e) {
            throw new IllegalArgumentException("not JSON", e);
        }
        if (node == null || !node.isArray() || node.size() < 2) {
            return;
        }
        String type = node.get(0).asText();
        if (!subscriptionId.equals(node.get(1).asText())) {
            return;
        }
        switch (type) {
            case "EVENT" -> {
                if (node.size() >= 3) {
                    synchronized (events) {
                        events.add(toEvent(node.get(2)));
                    }
                }
            }
            case "EOSE" -> {
                synchronized (events) {
                    result.complete(List.copyOf(events));
                }
            }
            case "CLOSED" -> result.completeExceptionally(new VoucherNostrException(
                    "relay " + relayUrl + " closed the subscription: " + (node.size() > 2 ? node.get(2).asText() : "")));
            default -> {
            }
        }
    }

    static GenericEvent toEvent(JsonNode e) {
        List<BaseTag> tags = new ArrayList<>();
        for (JsonNode t : e.get("tags")) {
            List<String> params = new ArrayList<>();
            for (int i = 1; i < t.size(); i++) {
                params.add(t.get(i).asText());
            }
            tags.add(BaseTag.create(t.get(0).asText(), params));
        }
        GenericEvent event = new GenericEvent(new PublicKey(e.get("pubkey").asText()), e.get("kind").asInt(),
                tags, e.get("content").asText());
        event.setCreatedAt(e.get("created_at").asLong());
        event.setId(e.get("id").asText());
        event.setSignature(Signature.fromString(e.get("sig").asText()));
        return event;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        result.completeExceptionally(new VoucherNostrException(
                "relay " + relayUrl + " closed the connection before EOSE: " + statusCode));
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        result.completeExceptionally(new VoucherNostrException("relay " + relayUrl + " errored", error));
    }

    private void finish(WebSocket socket) {
        try {
            if (!socket.isOutputClosed()) {
                socket.sendText(JSON.createArrayNode().add("CLOSE").add(subscriptionId).toString(), true)
                        .thenCompose(s -> s.sendClose(WebSocket.NORMAL_CLOSURE, ""))
                        .exceptionally(x -> {
                            socket.abort();
                            return null;
                        });
            }
        } catch (RuntimeException e) {
            socket.abort();
        }
    }
}
