package blater.nq.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TestFiles {
    private TestFiles() {
    }

    public static String write(Path directory, String filename, String content) throws IOException {
        Path path = directory.resolve(filename);
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return path.toAbsolutePath().toString();
    }
}
