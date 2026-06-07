package io.safelang.compiler.jvm;

import io.safelang.runtime.SAFEValue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Packages generated classes plus the SAFE runtime into a self-contained executable jar runnable
 * with {@code java -jar program.jar}.
 *
 * <p>The runtime is copied from this backend's own code source — whichever {@code safe-lang} jar or
 * {@code target/classes} directory is on the running classpath. To keep packaging trivial, the
 * whole {@code io/safelang/**} class tree is bundled; unused classes (e.g. the ANTLR-backed parser)
 * are never loaded at run time, so the absence of ANTLR in the bundle is harmless.
 */
final class JarAssembler {

  private static final String PREFIX = "io/safelang/";

  void assemble(final Path output, final String mainBinaryName, final Map<String, byte[]> classes)
      throws IOException {
    final var manifest = new Manifest();
    final var attributes = manifest.getMainAttributes();
    attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    attributes.put(Attributes.Name.MAIN_CLASS, mainBinaryName.replace('/', '.'));

    final var written = new HashSet<String>();
    try (var jar = new JarOutputStream(Files.newOutputStream(output), manifest)) {
      for (final var entry : classes.entrySet()) {
        write(jar, entry.getKey().replace('.', '/') + ".class", entry.getValue(), written);
      }
      bundleRuntime(jar, written);
    }
  }

  private void bundleRuntime(final JarOutputStream jar, final Set<String> written)
      throws IOException {
    final var source = SAFEValue.class.getProtectionDomain().getCodeSource();
    if (source == null) {
      throw new IOException("Cannot locate the SAFE runtime to bundle into the jar");
    }
    final Path location;
    try {
      location = Path.of(source.getLocation().toURI());
    } catch (final Exception exception) {
      throw new IOException("Invalid runtime location: " + source.getLocation(), exception);
    }
    if (Files.isDirectory(location)) {
      bundleDirectory(jar, location, written);
    } else {
      bundleJar(jar, location, written);
    }
  }

  private void bundleDirectory(
      final JarOutputStream jar, final Path root, final Set<String> written) throws IOException {
    final var base = root.resolve(PREFIX);
    if (!Files.isDirectory(base)) {
      return;
    }
    try (Stream<Path> files = Files.walk(base)) {
      for (final var file : (Iterable<Path>) files::iterator) {
        if (Files.isRegularFile(file) && file.toString().endsWith(".class")) {
          final var name = root.relativize(file).toString().replace('\\', '/');
          write(jar, name, Files.readAllBytes(file), written);
        }
      }
    }
  }

  private void bundleJar(final JarOutputStream jar, final Path archive, final Set<String> written)
      throws IOException {
    try (var source = new JarFile(archive.toFile())) {
      final var entries = source.entries();
      while (entries.hasMoreElements()) {
        final var entry = entries.nextElement();
        final var name = entry.getName();
        if (name.startsWith(PREFIX) && name.endsWith(".class")) {
          try (var stream = source.getInputStream(entry)) {
            write(jar, name, stream.readAllBytes(), written);
          }
        }
      }
    }
  }

  private void write(
      final JarOutputStream jar, final String name, final byte[] bytes, final Set<String> written)
      throws IOException {
    if (!written.add(name)) {
      return;
    }
    jar.putNextEntry(new JarEntry(name));
    jar.write(bytes);
    jar.closeEntry();
  }
}
