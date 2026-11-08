package com.kece.judging.artifact;

import com.kece.judging.artifact.JudgeManifest.Artifact;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JudgeArtifactStore {

    public static final long MAX_BYTES = 100L * 1024 * 1024;
    private final Path root;

    public JudgeArtifactStore(
            @Value("${kece.judging.artifacts-dir:data/judge-artifacts}") String directory) {
        root = Path.of(directory).toAbsolutePath().normalize();
    }

    public Artifact store(InputStream source) throws IOException {
        Files.createDirectories(root);
        Path staged = Files.createTempFile(root, ".upload-", ".part");
        try {
            var digest = sha256();
            long size;
            try (var channel = FileChannel.open(staged, StandardOpenOption.WRITE);
                    var target =
                            new DigestOutputStream(Channels.newOutputStream(channel), digest)) {
                size = copy(source, target);
                channel.force(true);
            }
            var artifact = new Artifact(HexFormat.of().formatHex(digest.digest()), size);
            Path destination = path(artifact.digest());
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                try (var ignored = openVerified(artifact)) {
                    return artifact;
                }
            }
            // Concurrent publishers of the same digest supply identical bytes.
            Files.move(
                    staged,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return artifact;
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    public InputStream openVerified(Artifact artifact) throws IOException {
        Path source = path(artifact.digest());
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Artifact is missing or is not a regular file");
        }
        var channel = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            if (channel.size() != artifact.size() || artifact.size() > MAX_BYTES) {
                throw new IOException("Artifact size mismatch");
            }
            var digest = sha256();
            var input = new DigestInputStream(Channels.newInputStream(channel), digest);
            copy(input, java.io.OutputStream.nullOutputStream());
            if (!artifact.digest().equals(HexFormat.of().formatHex(digest.digest()))) {
                throw new IOException("Artifact digest mismatch");
            }
            channel.position(0);
            return Channels.newInputStream(channel);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    static String digest(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    private Path path(String digest) {
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid artifact digest");
        }
        return root.resolve(digest);
    }

    private static long copy(InputStream source, java.io.OutputStream target) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        for (int n; (n = source.read(buffer)) != -1; ) {
            total += n;
            if (total > MAX_BYTES) {
                throw new IOException("Artifact exceeds size limit");
            }
            target.write(buffer, 0, n);
        }
        return total;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
