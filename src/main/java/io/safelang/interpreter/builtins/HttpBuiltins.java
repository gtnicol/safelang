package io.safelang.interpreter.builtins;

import io.safelang.runtime.BuiltinExecutors;
import io.safelang.runtime.HostCallback;
import io.safelang.runtime.SAFEValue;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * HTTP client and server builtins (the {@code http} module). Client calls use {@link HttpClient}
 * (HTTPS for free). The server runs a raw {@link ServerSocket} accept loop <em>on the calling
 * thread</em> so the user handler — invoked through {@link HostCallback} — sees the same backend
 * thread-locals (a background dispatch thread would not), mirroring the native C socket loop.
 */
public final class HttpBuiltins {

  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

  // Resource limits. Package-private and non-final so tests can shrink them; production keeps these
  // documented defaults. See the network section of README/CLAUDE for rationale.
  static long CLIENT_TIMEOUT = 30; // seconds, full request
  static long CLIENT_MAX_RESPONSE = 32L * 1024 * 1024; // 32 MiB
  static int SERVER_MAX_BODY = 8 * 1024 * 1024; // 8 MiB
  static int SERVER_MAX_HEADER_BYTES = 64 * 1024; // 64 KiB, all header lines
  static int SERVER_MAX_HEADERS = 100;
  static int SERVER_MAX_LINE = 16 * 1024; // 16 KiB, one request/header line
  static int SERVER_READ_TIMEOUT =
      3_000; // ms, per connection (bounds slowloris on a 1-thread loop)
  static int SERVER_ACCEPT_POLL = 1_000; // ms, so serve_until can re-check its stop predicate

  private HttpBuiltins() {}

  public static void register(
      final BuiltinExecutors executors, final io.safelang.runtime.HostPolicy policy) {
    executors.register(
        "http_get", args -> request("GET", args.getFirst().asString(), Map.of(), null, policy));
    executors.register(
        "http_post",
        args ->
            request("POST", args.getFirst().asString(), Map.of(), args.get(1).asString(), policy));
    executors.register(
        "http_request",
        args ->
            request(
                args.getFirst().asString(),
                args.get(1).asString(),
                headerMap(args.get(2)),
                args.get(3).asString(),
                policy));
    executors.register("http_serve", args -> serve(args, policy));
  }

  private static SAFEValue request(
      final String method,
      final String url,
      final Map<String, String> headers,
      final String body,
      final io.safelang.runtime.HostPolicy policy) {
    if (!policy.egressAllowed(url)) {
      // Do not re-parse here: egressAllowed returns false for a malformed URL too, so URI.create
      // would throw an uncaught exception instead of yielding an Err. Report the raw url.
      return err("host not allowed by egress policy: " + url);
    }
    try {
      final var publisher =
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(body);
      final var builder =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(CLIENT_TIMEOUT))
              .method(method, publisher);
      for (final var entry : headers.entrySet()) {
        try {
          builder.header(entry.getKey(), entry.getValue());
        } catch (final IllegalArgumentException restricted) {
          // A forbidden/invalid request header is a policy violation, not a success — surface it
          // instead of silently dropping it.
          return err("request header not allowed: " + entry.getKey());
        }
      }
      final var response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
      final byte[] bytes;
      try (var stream = response.body()) {
        bytes = readCapped(stream, CLIENT_MAX_RESPONSE);
      }
      if (bytes == null) {
        return err("response exceeds " + CLIENT_MAX_RESPONSE + " bytes");
      }
      final Map<SAFEValue, SAFEValue> responseHeaders = new LinkedHashMap<>();
      for (final var entry : response.headers().map().entrySet()) {
        responseHeaders.put(
            SAFEValue.ofString(entry.getKey()),
            SAFEValue.ofString(String.join(", ", entry.getValue())));
      }
      return ok(
          response.statusCode(),
          new String(bytes, StandardCharsets.UTF_8),
          SAFEValue.ofMap(responseHeaders));
    } catch (final IOException exception) {
      return err("Request failed: " + exception.getMessage());
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      return err("Request interrupted");
    } catch (final IllegalArgumentException exception) {
      return err("Invalid request: " + exception.getMessage());
    }
  }

  /**
   * Read up to {@code cap} bytes; return null if the stream has more (caller turns it into Err).
   */
  private static byte[] readCapped(final InputStream stream, final long cap) throws IOException {
    final var buffer = new ByteArrayOutputStream();
    final var chunk = new byte[8192];
    int read;
    while ((read = stream.read(chunk)) != -1) {
      if (buffer.size() + read > cap) {
        return null;
      }
      buffer.write(chunk, 0, read);
    }
    return buffer.toByteArray();
  }

  private static Map<String, String> headerMap(final SAFEValue value) {
    final Map<String, String> result = new LinkedHashMap<>();
    for (final var entry : value.asMap().entrySet()) {
      result.put(entry.getKey().asString(), entry.getValue().asString());
    }
    return result;
  }

  private static SAFEValue serve(
      final List<SAFEValue> args, final io.safelang.runtime.HostPolicy policy) {
    final var port = (int) args.getFirst().asInt();
    final var handler = args.get(1);
    final var stop = args.get(2);
    final var cert = args.get(3).asString();
    final var key = args.get(4).asString();
    final var applier = HostCallback.current();
    try (var server = listen(port, cert, key, policy)) {
      // A short accept timeout lets the stop predicate be re-checked even when no connection
      // arrives, so serve_until is not wedged waiting for the next client.
      server.setSoTimeout(SERVER_ACCEPT_POLL);
      long count = 0;
      while (true) {
        final Socket socket;
        try {
          socket = server.accept();
        } catch (final java.net.SocketTimeoutException pollTick) {
          if (applier.apply(stop, List.of(SAFEValue.ofInt(count))).asBoolean()) {
            break;
          }
          continue;
        }
        try (socket) {
          socket.setSoTimeout(SERVER_READ_TIMEOUT);
          serveOne(socket, handler, applier);
        } catch (final java.net.SocketTimeoutException slow) {
          // Slowloris: the client stalled mid-request. Drop it and keep serving.
        } catch (final IOException connectionError) {
          // Client reset / went away. Drop it and keep serving.
        } catch (final RuntimeException error) {
          // Backstop: a malformed request (e.g. unparseable request line) or any other per-request
          // failure drops just this connection — the accept loop survives so one bad request cannot
          // take down the server.
          System.err.println("http:serve dropped a request: " + error.getMessage());
        }
        count++;
        if (applier.apply(stop, List.of(SAFEValue.ofInt(count))).asBoolean()) {
          break;
        }
      }
    } catch (final IOException exception) {
      throw new io.safelang.interpreter.InterpreterException(
          "http:serve failed: " + exception.getMessage(), exception);
    }
    return SAFEValue.ofVoid();
  }

  /** Aborts serve() so a malformed request or invalid handler response is surfaced, not hidden. */
  private static RuntimeException abort(final String message) {
    return new io.safelang.interpreter.InterpreterException("http:serve: " + message);
  }

  /**
   * Thrown for a recognized size-limit breach; serveOne answers with {@code status} and continues.
   */
  private static final class RequestTooLarge extends RuntimeException {
    final int status;

    RequestTooLarge(final int status) {
      this.status = status;
    }
  }

  private static ServerSocket listen(
      final int port,
      final String cert,
      final String key,
      final io.safelang.runtime.HostPolicy policy)
      throws IOException {
    // Bind the configured address (loopback by default) so a guest server is not exposed to the
    // whole network unless the embedder explicitly opens it up.
    final var address = java.net.InetAddress.getByName(policy.serveBind());
    if (cert.isEmpty() && key.isEmpty()) {
      return new ServerSocket(port, 0, address);
    }
    try {
      final var context = tlsContext(cert, key, policy);
      return context.getServerSocketFactory().createServerSocket(port, 0, address);
    } catch (final IOException exception) {
      throw exception;
    } catch (final Exception exception) {
      throw new IOException("TLS setup failed: " + exception.getMessage(), exception);
    }
  }

  private static void serveOne(
      final Socket socket, final SAFEValue handler, final HostCallback.Applier applier)
      throws IOException {
    // Buffer the socket input: readLine consumes a byte at a time, and a raw SocketInputStream
    // would
    // make one syscall per byte (tens of thousands for a large header section).
    final var in = new BufferedInputStream(socket.getInputStream());
    final var out = socket.getOutputStream();
    try {
      final var requestLine = readLine(in);
      if (requestLine == null || requestLine.isEmpty()) {
        return; // empty connection / probe — benign, keep serving
      }
      final var parts = requestLine.split(" ", 3);
      if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
        throw abort("malformed request line");
      }
      final var method = parts[0];
      final var path = parts[1];

      final Map<SAFEValue, SAFEValue> headers = new LinkedHashMap<>();
      int headerCount = 0;
      int headerBytes = 0;
      long contentLength = 0;
      String line;
      while ((line = readLine(in)) != null && !line.isEmpty()) {
        headerCount++;
        headerBytes += line.length() + 2;
        if (headerCount > SERVER_MAX_HEADERS || headerBytes > SERVER_MAX_HEADER_BYTES) {
          throw new RequestTooLarge(431);
        }
        final var colon = line.indexOf(':');
        if (colon < 0) {
          continue;
        }
        final var name = line.substring(0, colon).trim();
        final var value = line.substring(colon + 1).trim();
        headers.put(SAFEValue.ofString(name), SAFEValue.ofString(value));
        if (name.equalsIgnoreCase("Content-Length")) {
          try {
            contentLength = Long.parseLong(value);
          } catch (final NumberFormatException ignored) {
            contentLength = 0;
          }
        }
      }
      if (contentLength < 0 || contentLength > SERVER_MAX_BODY) {
        throw new RequestTooLarge(413);
      }
      final var body =
          contentLength > 0
              ? new String(readN(in, (int) contentLength), StandardCharsets.UTF_8)
              : "";

      final Map<String, SAFEValue> request = new LinkedHashMap<>();
      request.put("method", SAFEValue.ofString(method));
      request.put("path", SAFEValue.ofString(path));
      request.put("headers", SAFEValue.ofMap(headers));
      request.put("body", SAFEValue.ofString(body));

      try {
        final var response =
            applier.apply(handler, List.of(SAFEValue.ofObject("Request", request)));
        writeResponse(out, response);
      } catch (final RuntimeException handlerError) {
        // A handler runtime error (bad user logic) or a malformed handler response must not take
        // down the server — answer 500 and keep serving. The failure is logged for the operator.
        System.err.println("http:serve handler error: " + handlerError.getMessage());
        writeStatus(out, 500);
      }
    } catch (final RequestTooLarge limit) {
      writeStatus(out, limit.status);
    }
  }

  private static void writeResponse(final OutputStream out, final SAFEValue response)
      throws IOException {
    final var fields = response.fields();
    final var status = (int) fields.get("status").asInt();
    final var body = fields.get("body").asString().getBytes(StandardCharsets.UTF_8);
    final var builder = new StringBuilder();
    builder.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
    final var headers = fields.get("headers");
    if (headers != null) {
      for (final var entry : headers.asMap().entrySet()) {
        final var name = entry.getKey().asString();
        final var value = entry.getValue().asString();
        // Reject CR/LF/NUL in handler-supplied headers — otherwise a value carrying "\r\n" could
        // split the response (header/response injection). The handler bug is surfaced, not emitted.
        if (!clean(name) || !clean(value)) {
          throw abort("response header contains control characters: " + name);
        }
        builder.append(name).append(": ").append(value).append("\r\n");
      }
    }
    builder.append("Content-Length: ").append(body.length).append("\r\n");
    builder.append("Connection: close\r\n\r\n");
    out.write(builder.toString().getBytes(StandardCharsets.UTF_8));
    out.write(body);
    out.flush();
  }

  /** Write a bodyless status response (used for 413/431 rejections). */
  private static void writeStatus(final OutputStream out, final int status) throws IOException {
    final var head =
        "HTTP/1.1 "
            + status
            + " "
            + reason(status)
            + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
    out.write(head.getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  /** True when {@code text} has no CR, LF, or NUL — safe to place in a header line. */
  private static boolean clean(final String text) {
    for (var i = 0; i < text.length(); i++) {
      final var c = text.charAt(i);
      if (c == '\r' || c == '\n' || c == '\0') {
        return false;
      }
    }
    return true;
  }

  private static String readLine(final InputStream in) throws IOException {
    final var buffer = new ByteArrayOutputStream();
    int previous = -1;
    int current;
    while ((current = in.read()) != -1) {
      if (previous == '\r' && current == '\n') {
        final var bytes = buffer.toByteArray();
        return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.UTF_8);
      }
      buffer.write(current);
      previous = current;
      if (buffer.size() > SERVER_MAX_LINE) {
        throw new RequestTooLarge(431); // an over-long request/header line
      }
    }
    return buffer.size() == 0 ? null : buffer.toString(StandardCharsets.UTF_8);
  }

  private static byte[] readN(final InputStream in, final int length) throws IOException {
    final var bytes = new byte[length];
    int read = 0;
    while (read < length) {
      final var n = in.read(bytes, read, length - read);
      if (n < 0) {
        break;
      }
      read += n;
    }
    return read == length ? bytes : java.util.Arrays.copyOf(bytes, read);
  }

  private static SSLContext tlsContext(
      final String certPath, final String keyPath, final io.safelang.runtime.HostPolicy policy)
      throws Exception {
    // Reading cert/key touches the host filesystem, so require FILESYSTEM (granting NETWORK alone
    // must not open a file-read surface) and confine both paths to the fs jail with a size cap —
    // the same discipline the file/binary builtins use.
    if (!policy.capabilities().granted(io.safelang.runtime.Capability.FILESYSTEM)) {
      throw new IOException(
          "TLS requires the FILESYSTEM capability to read the certificate and key");
    }
    final var certResolved = policy.resolve(certPath);
    final var keyResolved = policy.resolve(keyPath);
    if (Files.size(certResolved) > FileBuiltins.MAX_FILE_BYTES
        || Files.size(keyResolved) > FileBuiltins.MAX_FILE_BYTES) {
      throw new IOException("TLS certificate or key exceeds the maximum size");
    }
    final Certificate[] certs;
    // try-with-resources: CertificateFactory does not close the stream, so leaving it to GC leaks
    // an
    // fd per call (EMFILE over a long-running server that rotates certs).
    try (var in = Files.newInputStream(certResolved)) {
      certs =
          CertificateFactory.getInstance("X.509")
              .generateCertificates(in)
              .toArray(new Certificate[0]);
    }
    final var key = privateKey(Files.readString(keyResolved));
    final var password = new char[0];
    final var store = KeyStore.getInstance("PKCS12");
    store.load(null, password);
    store.setKeyEntry("server", key, password, certs);
    final var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    managers.init(store, password);
    final var context = SSLContext.getInstance("TLS");
    context.init(managers.getKeyManagers(), null, null);
    return context;
  }

  private static PrivateKey privateKey(final String pem) throws Exception {
    final var base64 =
        pem.replaceAll("-----BEGIN (RSA |EC )?PRIVATE KEY-----", "")
            .replaceAll("-----END (RSA |EC )?PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    final var der = Base64.getDecoder().decode(base64);
    final var spec = new PKCS8EncodedKeySpec(der);
    for (final var algorithm : List.of("RSA", "EC")) {
      try {
        return KeyFactory.getInstance(algorithm).generatePrivate(spec);
      } catch (final Exception next) {
        // Try the next algorithm.
      }
    }
    throw new IllegalArgumentException("Unsupported private key (expected PKCS#8 RSA or EC PEM)");
  }

  private static SAFEValue ok(final int status, final String body, final SAFEValue headers) {
    final Map<String, SAFEValue> fields = new LinkedHashMap<>();
    fields.put("status", SAFEValue.ofInt(status));
    fields.put("body", SAFEValue.ofString(body));
    fields.put("headers", headers);
    return SAFEValue.ofEnum("HttpResult", "Ok", List.of(SAFEValue.ofObject("Response", fields)));
  }

  private static SAFEValue err(final String message) {
    return SAFEValue.ofEnum("HttpResult", "Err", List.of(SAFEValue.ofString(message)));
  }

  private static String reason(final int status) {
    return switch (status) {
      case 200 -> "OK";
      case 201 -> "Created";
      case 204 -> "No Content";
      case 301 -> "Moved Permanently";
      case 302 -> "Found";
      case 400 -> "Bad Request";
      case 401 -> "Unauthorized";
      case 403 -> "Forbidden";
      case 404 -> "Not Found";
      case 413 -> "Payload Too Large";
      case 431 -> "Request Header Fields Too Large";
      case 500 -> "Internal Server Error";
      default -> "Status";
    };
  }
}
