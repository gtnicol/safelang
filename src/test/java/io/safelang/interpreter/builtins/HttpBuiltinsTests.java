package io.safelang.interpreter.builtins;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.safelang.runtime.BuiltinExecutors;
import io.safelang.runtime.HostCallback;
import io.safelang.runtime.SAFEValue;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the HTTP client and server builtins, including the {@link HostCallback} serve path and
 * the resource-limit guards (timeout/size caps, slowloris, response splitting). Lives in the
 * builtin package so it can shrink the package-private limit fields; {@link #teardown} restores
 * them.
 */
class HttpBuiltinsTests {

  private BuiltinExecutors executors;
  private HttpServer probe;
  private int probePort;

  private long savedClientTimeout;
  private long savedClientMax;
  private int savedBody;
  private int savedHeaders;
  private int savedHeaderBytes;
  private int savedLine;
  private int savedReadTimeout;
  private int savedAcceptPoll;

  @BeforeEach
  void setup() throws Exception {
    executors = new BuiltinExecutors();
    // The client tests deliberately hit a loopback probe server; loopback is blocked by the default
    // SSRF policy, so explicitly allowlist it (an embedder trusts a named internal target).
    HttpBuiltins.register(
        executors,
        io.safelang.runtime.HostPolicy.trusted().toBuilder()
            .netAllow(java.util.List.of("127.0.0.1"))
            .build());
    savedClientTimeout = HttpBuiltins.CLIENT_TIMEOUT;
    savedClientMax = HttpBuiltins.CLIENT_MAX_RESPONSE;
    savedBody = HttpBuiltins.SERVER_MAX_BODY;
    savedHeaders = HttpBuiltins.SERVER_MAX_HEADERS;
    savedHeaderBytes = HttpBuiltins.SERVER_MAX_HEADER_BYTES;
    savedLine = HttpBuiltins.SERVER_MAX_LINE;
    savedReadTimeout = HttpBuiltins.SERVER_READ_TIMEOUT;
    savedAcceptPoll = HttpBuiltins.SERVER_ACCEPT_POLL;

    probe = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    probe.createContext(
        "/big",
        exchange -> {
          final var body = new byte[200_000];
          exchange.sendResponseHeaders(200, body.length);
          try (var stream = exchange.getResponseBody()) {
            stream.write(body);
          }
        });
    probe.createContext(
        "/",
        exchange -> {
          final var body =
              ("echo:" + exchange.getRequestURI().getPath()).getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (var stream = exchange.getResponseBody()) {
            stream.write(body);
          }
        });
    probe.start();
    probePort = probe.getAddress().getPort();
  }

  @AfterEach
  void teardown() {
    if (probe != null) {
      probe.stop(0);
    }
    HostCallback.clear();
    HttpBuiltins.CLIENT_TIMEOUT = savedClientTimeout;
    HttpBuiltins.CLIENT_MAX_RESPONSE = savedClientMax;
    HttpBuiltins.SERVER_MAX_BODY = savedBody;
    HttpBuiltins.SERVER_MAX_HEADERS = savedHeaders;
    HttpBuiltins.SERVER_MAX_HEADER_BYTES = savedHeaderBytes;
    HttpBuiltins.SERVER_MAX_LINE = savedLine;
    HttpBuiltins.SERVER_READ_TIMEOUT = savedReadTimeout;
    HttpBuiltins.SERVER_ACCEPT_POLL = savedAcceptPoll;
  }

  // ---- client ----------------------------------------------------------------

  @Test
  void testGetReturnsStatusBodyAndHeaders() {
    final var result =
        executors
            .get("http_get")
            .execute(List.of(SAFEValue.ofString("http://127.0.0.1:" + probePort + "/hi")));
    assertEquals("Ok", result.variant());
    final var response = result.data().get(0);
    assertEquals(200L, response.fields().get("status").asInt());
    assertEquals("echo:/hi", response.fields().get("body").asString());
  }

  @Test
  void testGetBadHostReturnsErr() {
    final var result =
        executors.get("http_get").execute(List.of(SAFEValue.ofString("http://127.0.0.1:1/nope")));
    assertEquals("Err", result.variant());
  }

  @Test
  void testForbiddenRequestHeaderReturnsErr() {
    final Map<SAFEValue, SAFEValue> headers = new LinkedHashMap<>();
    headers.put(SAFEValue.ofString("Host"), SAFEValue.ofString("evil"));
    final var result =
        executors
            .get("http_request")
            .execute(
                List.of(
                    SAFEValue.ofString("GET"),
                    SAFEValue.ofString("http://127.0.0.1:" + probePort + "/hi"),
                    SAFEValue.ofMap(headers),
                    SAFEValue.ofString("")));
    assertEquals("Err", result.variant());
    assertTrue(result.data().get(0).asString().contains("not allowed"));
  }

  @Test
  void testResponseCapReturnsErr() {
    HttpBuiltins.CLIENT_MAX_RESPONSE = 1024;
    final var result =
        executors
            .get("http_get")
            .execute(List.of(SAFEValue.ofString("http://127.0.0.1:" + probePort + "/big")));
    assertEquals("Err", result.variant());
    assertTrue(result.data().get(0).asString().contains("exceeds"));
  }

  // ---- server ----------------------------------------------------------------

  @Test
  void testServeInvokesHandlerHonorsHeadersAndStops() throws Exception {
    final var port = startServer(okHandlerStopAfter(1));
    final var client = HttpClient.newHttpClient();
    HttpResponse<String> response = null;
    for (int attempt = 0; attempt < 50 && response == null; attempt++) {
      try {
        response =
            client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/abc")).build(),
                HttpResponse.BodyHandlers.ofString());
      } catch (final Exception retry) {
        Thread.sleep(50);
      }
    }
    serverThread.join(5000);
    assertNotNull(response, "server never accepted a connection");
    assertEquals(200, response.statusCode());
    assertEquals("served:/abc", response.body());
    assertEquals("v", response.headers().firstValue("X-H").orElse(""));
    assertFalse(serverThread.isAlive(), "serve must return once the stop predicate is satisfied");
  }

  @Test
  void testOversizedBodyGets413() throws Exception {
    HttpBuiltins.SERVER_MAX_BODY = 1024;
    final var port = startServer(okHandlerStopAfter(1));
    final var status = rawRequest(port, "POST /x HTTP/1.1\r\nContent-Length: 100000\r\n\r\n");
    serverThread.join(5000);
    assertEquals("HTTP/1.1 413 Payload Too Large", status);
    assertFalse(serverThread.isAlive());
  }

  @Test
  void testHeaderFloodGets431() throws Exception {
    HttpBuiltins.SERVER_MAX_HEADERS = 5;
    final var port = startServer(okHandlerStopAfter(1));
    final var request = new StringBuilder("GET /x HTTP/1.1\r\n");
    for (var i = 0; i < 20; i++) {
      request.append("X-H").append(i).append(": v\r\n");
    }
    request.append("\r\n");
    final var status = rawRequest(port, request.toString());
    serverThread.join(5000);
    assertEquals("HTTP/1.1 431 Request Header Fields Too Large", status);
    assertFalse(serverThread.isAlive());
  }

  @Test
  void testResponseSplittingReturns500AndKeepsServing() throws Exception {
    // A handler returning a CRLF-injected header is a per-request failure: the split response is
    // never emitted (the abort fires before any bytes are written), the bad route gets a 500, and
    // the server stays up instead of crashing.
    final var failure = new AtomicReference<Throwable>();
    final HostCallback.Applier applier =
        (function, args) -> {
          if ("handler".equals(function.asString())) {
            return response(200, "x", Map.of("X-Bad", "a\r\nInjected: 1"));
          }
          return SAFEValue.ofBoolean(args.get(0).asInt() >= 1);
        };
    final var port = startServer(applier, failure);
    final var statusLine = rawRequest(port, "GET /x HTTP/1.1\r\n\r\n");
    serverThread.join(5000);
    assertNotNull(statusLine);
    assertTrue(
        statusLine.contains("500"),
        "a response-splitting handler must get a 500, got " + statusLine);
    assertFalse(statusLine.contains("Injected"), "the injected header must not be emitted");
    assertNull(failure.get(), "serve must not crash on a bad handler response");
    assertFalse(serverThread.isAlive(), "serve must keep running and stop normally");
  }

  @Test
  void testHandlerExceptionReturns500AndKeepsServing() throws Exception {
    // A handler that throws a runtime error (bad user logic) gets a 500 without killing the server.
    final var failure = new AtomicReference<Throwable>();
    final HostCallback.Applier applier =
        (function, args) -> {
          if ("handler".equals(function.asString())) {
            throw new io.safelang.interpreter.InterpreterException("missing key in handler");
          }
          return SAFEValue.ofBoolean(args.get(0).asInt() >= 1);
        };
    final var port = startServer(applier, failure);
    final var statusLine = rawRequest(port, "GET /x HTTP/1.1\r\n\r\n");
    serverThread.join(5000);
    assertNotNull(statusLine);
    assertTrue(statusLine.contains("500"), "a throwing handler must get a 500, got " + statusLine);
    assertNull(failure.get(), "serve must not crash on a handler exception");
    assertFalse(serverThread.isAlive());
  }

  @Test
  void testSlowlorisIsDroppedAndServerStaysResponsive() throws Exception {
    HttpBuiltins.SERVER_READ_TIMEOUT = 500;
    final var port = startServer(okHandlerStopAfter(1));
    // Send a request line but never the terminating blank line — the server must time out, drop the
    // connection, and return (count reaches the stop threshold), not hang.
    try (var socket = new Socket("127.0.0.1", port)) {
      socket.getOutputStream().write("GET /x HTTP/1.1\r\n".getBytes(StandardCharsets.UTF_8));
      socket.getOutputStream().flush();
      serverThread.join(5000);
    }
    assertFalse(serverThread.isAlive(), "serve must drop a stalled client and move on");
  }

  @Test
  void testServeUntilStopsWithoutAConnection() throws Exception {
    // A stop predicate that is immediately true must let serve_until return on the first accept
    // poll tick, with no connection ever arriving.
    final HostCallback.Applier applier = (function, args) -> SAFEValue.ofBoolean(true);
    startServer(applier, new AtomicReference<>());
    serverThread.join(5000);
    assertFalse(
        serverThread.isAlive(), "serve_until must stop on a poll tick, not block on accept");
  }

  // ---- helpers ---------------------------------------------------------------

  private Thread serverThread;

  private int startServer(final HostCallback.Applier applier) throws Exception {
    return startServer(applier, new AtomicReference<>());
  }

  private int startServer(
      final HostCallback.Applier applier, final AtomicReference<Throwable> failure)
      throws Exception {
    final var port = freePort();
    serverThread =
        new Thread(
            () -> {
              HostCallback.set(applier);
              try {
                executors
                    .get("http_serve")
                    .execute(
                        List.of(
                            SAFEValue.ofInt(port),
                            SAFEValue.ofString("handler"),
                            SAFEValue.ofString("stop"),
                            SAFEValue.ofString(""),
                            SAFEValue.ofString("")));
              } catch (final Throwable thrown) {
                failure.set(thrown);
              }
            });
    serverThread.setDaemon(true);
    serverThread.start();
    Thread.sleep(300); // let the listener bind
    return port;
  }

  /**
   * Applier whose handler replies 200/"served:<path>"/{X-H:v} and whose stop fires at {@code n}.
   */
  private static HostCallback.Applier okHandlerStopAfter(final int n) {
    return (function, args) -> {
      if ("handler".equals(function.asString())) {
        return response(
            200, "served:" + args.get(0).fields().get("path").asString(), Map.of("X-H", "v"));
      }
      return SAFEValue.ofBoolean(args.get(0).asInt() >= n);
    };
  }

  private static SAFEValue response(
      final int status, final String body, final Map<String, String> headers) {
    final Map<String, SAFEValue> fields = new LinkedHashMap<>();
    fields.put("status", SAFEValue.ofInt(status));
    fields.put("body", SAFEValue.ofString(body));
    final Map<SAFEValue, SAFEValue> headerMap = new LinkedHashMap<>();
    headers.forEach((k, v) -> headerMap.put(SAFEValue.ofString(k), SAFEValue.ofString(v)));
    fields.put("headers", SAFEValue.ofMap(headerMap));
    return SAFEValue.ofObject("Response", fields);
  }

  /**
   * Send a raw request and return the response status line (or null if the connection was dropped).
   */
  private static String rawRequest(final int port, final String raw) throws IOException {
    try (var socket = new Socket("127.0.0.1", port)) {
      socket.getOutputStream().write(raw.getBytes(StandardCharsets.UTF_8));
      socket.getOutputStream().flush();
      final var reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      return reader.readLine();
    }
  }

  private static int freePort() throws IOException {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
