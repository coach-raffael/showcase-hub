package com.coachraffael.demos.agent;

import com.coachraffael.demos.common.Errors;
import com.coachraffael.demos.common.HubProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Spricht HTTP/1.0 über den Unix-Socket des demo-agent ({@code /run/demo-agent/agent.sock}, ins API-Container
 * gemountet). Kein Netzwerk-Port, keine Docker-Socket-Freigabe für den Hub. Jede Anfrage ist zusätzlich per
 * HMAC signiert ({@code X-Agent-Timestamp}, {@code X-Agent-Nonce}, {@code X-Agent-Signature} über Zeit, Nonce,
 * Methode und Pfad).
 */
@Component
public class UnixSocketAgentClient implements AgentClient {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$");
    private final HubProperties props;
    private final ObjectMapper json;

    public UnixSocketAgentClient(HubProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    @Override
    public boolean configured() {
        return props.agentSocket() != null && !props.agentSocket().isBlank()
                && props.agentKey() != null && !props.agentKey().isBlank();
    }

    @Override
    public Map<String, Object> run(String slug, Action action) {
        JsonNode r = call("POST", "/v1/demos/" + slug(slug) + "/" + action.name());
        return json.convertValue(r, Map.class);
    }

    @Override
    public DemoStatus status(String slug) {
        return toStatus(call("GET", "/v1/demos/" + slug(slug) + "/status"));
    }

    @Override
    public List<DemoStatus> statusAll() {
        JsonNode r = call("GET", "/v1/demos");
        List<DemoStatus> out = new ArrayList<>();
        r.path("demos").forEach(n -> out.add(toStatus(n)));
        return out;
    }

    @Override
    public String logs(String slug, int tail) {
        int t = Math.max(1, Math.min(tail, 500));
        return call("GET", "/v1/demos/" + slug(slug) + "/logs?tail=" + t).path("logs").asText("");
    }

    private DemoStatus toStatus(JsonNode n) {
        List<Container> containers = new ArrayList<>();
        n.path("containers").forEach(c -> containers.add(new Container(c.path("service").asText(),
                c.path("state").asText(), c.path("health").asText(""), c.path("memory").asText(""),
                c.path("cpu").asText(""))));
        return new DemoStatus(n.path("slug").asText(), n.path("state").asText("unknown"), containers);
    }

    private JsonNode call(String method, String path) {
        if (!configured()) {
            throw new Errors.Conflict("demo-agent ist nicht verbunden (AGENT_SOCKET/AGENT_KEY fehlen)");
        }
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String request = method + " " + path + " HTTP/1.0\r\n"
                + "Host: demo-agent\r\n"
                + "X-Agent-Timestamp: " + ts + "\r\n"
                + "X-Agent-Nonce: " + nonce + "\r\n"
                + "X-Agent-Signature: " + sign(props.agentKey(), ts, nonce, method, path) + "\r\n"
                + "Content-Length: 0\r\n\r\n";
        byte[] raw;
        try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            ch.connect(UnixDomainSocketAddress.of(Path.of(props.agentSocket())));
            ch.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.US_ASCII)));
            ch.shutdownOutput();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Channels.newInputStream(ch).transferTo(buf);
            raw = buf.toByteArray();
        } catch (IOException e) {
            throw new Errors.Conflict("demo-agent nicht erreichbar: " + e.getMessage());
        }
        String resp = new String(raw, StandardCharsets.UTF_8);
        int split = resp.indexOf("\r\n\r\n");
        if (split < 0) {
            throw new Errors.Conflict("demo-agent: unvollständige Antwort");
        }
        String statusLine = resp.substring(0, resp.indexOf("\r\n"));
        int code = Integer.parseInt(statusLine.split(" ")[1]);
        JsonNode body;
        try {
            body = json.readTree(resp.substring(split + 4));
        } catch (IOException e) {
            throw new Errors.Conflict("demo-agent: Antwort ist kein JSON");
        }
        if (code == 404) {
            throw new Errors.NotFound(body.path("error").asText("Demo auf dem Server nicht vorhanden – erst ausrollen"));
        }
        if (code / 100 != 2) {
            throw new Errors.Conflict("demo-agent (" + code + "): " + body.path("error").asText(statusLine));
        }
        return body;
    }

    /** HMAC-SHA256 über {@code ts\nnonce\nMETHOD\npath} – identisch im Agent (agent/demo_agent.py). */
    static String sign(String key, String ts, String nonce, String method, String path) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((ts + "\n" + nonce + "\n" + method + "\n" + path).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String slug(String slug) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("Slug ungültig: " + slug);
        }
        return slug;
    }
}
