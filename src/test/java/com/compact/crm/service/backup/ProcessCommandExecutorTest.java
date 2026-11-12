package com.compact.crm.service.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Runs real child processes (this JVM's own java launcher running a tiny
// single-file program), so it works the same on Windows and Linux.
class ProcessCommandExecutorTest {

    @TempDir
    Path dir;

    private static String java() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    private Path program(String name, String body) throws Exception {
        Path src = dir.resolve(name + ".java");
        Files.writeString(src, "public class " + name + " { public static void main(String[] a) throws Exception { " + body + " } }");
        return src;
    }

    @Test
    void capturesExitCodeStdoutAndStderr_andCleansUpItsTempFiles() throws Exception {

        Path src = program("Hello", "System.out.print(\"out:\" + System.getenv(\"CRM_TEST_VALUE\")); "
                + "System.err.print(\"err-text\"); System.exit(3);");
        Path work = dir.resolve("work");

        CommandExecutor.CommandResult r = new ProcessCommandExecutor(work)
                .run(List.of(java(), src.toString()), Map.of("CRM_TEST_VALUE", "42"), Duration.ofMinutes(1));

        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.stdout()).isEqualTo("out:42");
        assertThat(r.stderr()).isEqualTo("err-text");
        try (var files = Files.list(work)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void doesNotPassInheritedPgVariablesToTheTool() throws Exception {

        // PGAPPNAME is set only through the explicit environment; any PG* variable
        // of the host process is removed (cannot set one on this JVM, so check the
        // explicit one arrives and the child sees no other PG* variable).
        Path src = program("Env", "System.out.print(System.getenv().keySet().stream()"
                + ".filter(k -> k.toUpperCase().startsWith(\"PG\")).sorted().toList());");

        CommandExecutor.CommandResult r = new ProcessCommandExecutor(dir.resolve("w"))
                .run(List.of(java(), src.toString()), Map.of("PGAPPNAME", "crm-backup"), Duration.ofMinutes(1));

        assertThat(r.stdout()).isEqualTo("[PGAPPNAME]");
    }

    @Test
    void killsAProcessThatOutlivesTheTimeout() throws Exception {

        Path src = program("Sleeper", "Thread.sleep(120000);");
        long t0 = System.nanoTime();

        assertThatThrownBy(() -> new ProcessCommandExecutor(dir.resolve("w"))
                .run(List.of(java(), src.toString()), Map.of(), Duration.ofSeconds(3)))
                .isInstanceOf(CommandExecutor.CommandTimeoutException.class)
                .hasMessage("timed out after 3 s");

        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(60));
    }

    @Test
    void aMissingProgramIsAnIoError() {

        assertThatThrownBy(() -> new ProcessCommandExecutor(dir.resolve("w"))
                .run(List.of(dir.resolve("no-such-tool").toString()), Map.of(), Duration.ofSeconds(5)))
                .isInstanceOf(java.io.IOException.class);
    }
}
