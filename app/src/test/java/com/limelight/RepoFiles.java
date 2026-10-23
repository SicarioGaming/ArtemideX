package com.limelight;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Files of the repository, for tests that check the code against docs and build files. */
public final class RepoFiles {
    // Gradle runs unit tests in the module folder (app/), so the repository is one level up
    public static File root() {
        String prop = System.getProperty("repo.root");
        File root = prop != null ? new File(prop) : new File("..").getAbsoluteFile();
        if (!new File(root, "settings.gradle").isFile()) {
            throw new IllegalStateException("not the repository root: " + root);
        }
        return root;
    }

    public static File file(String path) {
        return new File(root(), path);
    }

    public static String read(String path) throws IOException {
        return new String(Files.readAllBytes(file(path).toPath()), StandardCharsets.UTF_8);
    }

    private RepoFiles() {}
}
