package com.compact.crm.service.backup;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// Real CommandExecutor: ProcessBuilder, no shell. stdout and stderr go to
// temporary files (never a pipe, so a chatty program cannot block on a full
// pipe buffer) that are read back with a size cap and always deleted. On a
// timeout or an interrupt the process and all of its children are killed.
public class ProcessCommandExecutor implements CommandExecutor {

    static final int MAX_STDOUT_BYTES = 8 * 1024 * 1024;
    static final int MAX_STDERR_BYTES = 64 * 1024;

    private final Path workDir;

    public ProcessCommandExecutor(Path workDir) {
        this.workDir = workDir;
    }

    @Override
    public CommandResult run(List<String> command, Map<String, String> environment, Duration timeout)
            throws IOException, InterruptedException {

        Files.createDirectories(workDir);
        Path out = Files.createTempFile(workDir, "cmd-", ".out");
        Path err = Files.createTempFile(workDir, "cmd-", ".err");

        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectOutput(out.toFile())
                    .redirectError(err.toFile());

            Map<String, String> env = builder.environment();
            // Never let a stray PGPASSWORD / PGHOST / PGSERVICE from the host
            // environment decide which database the tool talks to.
            env.keySet().removeIf(key -> key.toUpperCase().startsWith("PG"));
            env.putAll(environment);

            Process process = builder.start();
            process.getOutputStream().close(); // no input: a tool that prompts gets EOF, never hangs
            boolean finished;
            try {
                finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                kill(process);
                throw e;
            }

            if (!finished) {
                kill(process);
                throw new CommandTimeoutException("timed out after " + humanize(timeout));
            }

            return new CommandResult(process.exitValue(), readHead(out, MAX_STDOUT_BYTES), readTail(err, MAX_STDERR_BYTES));

        } finally {
            deleteQuietly(out);
            deleteQuietly(err);
        }
    }

    // Best effort, never masks the real outcome. On Windows a just-killed
    // process can hold its output file open for a moment, so retry briefly.
    private static void deleteQuietly(Path file) {
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                Files.deleteIfExists(file);
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        file.toFile().deleteOnExit();
    }

    private static void kill(Process process) throws InterruptedException {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
    }

    static String humanize(Duration d) {
        long s = d.toSeconds();
        return s >= 60 && s % 60 == 0 ? (s / 60) + " min" : s + " s";
    }

    private static String readHead(Path file, int max) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            byte[] buf = new byte[(int) Math.min(f.length(), max)];
            f.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    private static String readTail(Path file, int max) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            long len = f.length();
            int n = (int) Math.min(len, max);
            f.seek(len - n);
            byte[] buf = new byte[n];
            f.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }
}
