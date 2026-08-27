package com.rhythmatician.lodiffusion.lint;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class TestAnnotationFormatTest {
    
    @Test
    @SuppressWarnings("null")
    @SuppressWarnings("null")
    void testAllTestAnnotationsAreOnTheirOwnLine() throws IOException {
        Path root = Paths.get("src/test/java");
        try (Stream<Path> files = Files.walk(root)) {
            List<String> violations = files
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.toString().contains("TestAnnotationFormatTest.java")) // Exclude self
                .flatMap(path -> {
                    try {
                        List<String> lines = Files.readAllLines(path);
                        return lines.stream()
                            .map(String::strip)
                            .filter(line -> line.contains("@Test") && !line.matches("^@Test\\s*$"))
                            .map(line -> path + ": " + line);
                    } catch (IOException e) {
                        return Stream.of("ERROR reading: " + path);
                    }
                })
                .collect(Collectors.toList());

            if (!violations.isEmpty()) {
                String message = "❌ Improper @Test formatting detected:\n" +
                                 String.join("\n", violations) +
                                 "\n\nEach @Test should be on its own line, e.g.:\n\n    @Test\n    void foo() {}\n";
                fail(message);
            }
        }
    }
}
