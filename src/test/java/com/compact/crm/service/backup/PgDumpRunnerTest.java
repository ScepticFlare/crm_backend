package com.compact.crm.service.backup;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PgDumpRunnerTest {

    private static final String PASSWORD = "Sup3r-Secret-Pw";
    private static final String HOST = "aws-0-region.pooler.example.com";
    private static final String USER = "postgres.projectref";

    private static final String GOOD_LIST = String.join("\n",
            ";",
            "; Archive created at 2026-11-11 10:00:00 UTC",
            ";     dbname: postgres",
            ";     Dumped from database version: 17.4",
            ";     Dumped by pg_dump version: 17.6 (Ubuntu 17.6-1.pgdg24.04+1)",
            ";",
            "215; 1259 16390 TABLE public employees postgres",
            "3401; 0 16390 TABLE DATA public employees postgres",
            "3402; 0 16400 TABLE DATA public flyway_schema_history postgres",
            "3403; 0 16410 TABLE DATA public leads postgres",
            "");

    private final BackupProperties props = new BackupProperties();
    private final PostgresTarget target = PostgresTarget.fromJdbc(
            "jdbc:postgresql://" + HOST + ":5432/postgres?sslmode=require", USER, PASSWORD);

    // Records what would have been run and answers with a canned result.
    private static final class FakeExecutor implements CommandExecutor {
        final List<List<String>> commands = new ArrayList<>();
        final List<Map<String, String>> envs = new ArrayList<>();
        CommandResult result = new CommandResult(0, "", "");
        IOException failure;

        @Override
        public CommandResult run(List<String> command, Map<String, String> environment, Duration timeout) throws IOException {
            commands.add(command);
            envs.add(environment);
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    @Test
    void dump_runsAReadOnlyCustomFormatDumpOfPublic_withThePasswordOnlyInTheEnvironment() throws Exception {

        FakeExecutor exec = new FakeExecutor();
        new PgDumpRunner(props, exec, target).dump(Path.of("out.dump"));

        List<String> cmd = exec.commands.get(0);
        assertThat(cmd.get(0)).isEqualTo("pg_dump");
        assertThat(cmd).contains("--format=custom", "--schema=public", "--no-owner", "--no-privileges", "--no-password",
                "--host=" + HOST, "--port=5432", "--username=" + USER, "--dbname=postgres");
        assertThat(cmd).noneMatch(a -> a.contains(PASSWORD));
        // nothing that could write to or drop anything in the source database
        assertThat(cmd).noneMatch(a -> a.contains("--clean") || a.contains("--create") || a.startsWith("--section"));

        Map<String, String> env = exec.envs.get(0);
        assertThat(env).containsEntry("PGPASSWORD", PASSWORD).containsEntry("PGSSLMODE", "require")
                .containsEntry("PGCONNECT_TIMEOUT", "15");
    }

    @Test
    void sslMode_followsConfigThenUrlThenRequireOrPreferForLocalhost() {

        FakeExecutor exec = new FakeExecutor();
        assertThat(new PgDumpRunner(props, exec, target).sslMode()).isEqualTo("require");

        PostgresTarget noSsl = PostgresTarget.fromJdbc("jdbc:postgresql://db.example.com/crm", "u", "p");
        assertThat(new PgDumpRunner(props, exec, noSsl).sslMode()).isEqualTo("require");

        PostgresTarget local = PostgresTarget.fromJdbc("jdbc:postgresql://localhost:5432/crm", "u", "p");
        assertThat(new PgDumpRunner(props, exec, local).sslMode()).isEqualTo("prefer");

        props.setSslMode("verify-full");
        assertThat(new PgDumpRunner(props, exec, local).sslMode()).isEqualTo("verify-full");
    }

    @Test
    void dumpFailure_isReportedWithTheExitCode_andScrubbedOfConnectionDetails() {

        FakeExecutor exec = new FakeExecutor();
        exec.result = new CommandExecutor.CommandResult(1, "",
                "pg_dump: error: connection to server at \"" + HOST + "\" (52.1.2.3), port 5432 failed: "
                        + "FATAL: password authentication failed for user \"" + USER + "\" password=" + PASSWORD);

        assertThatThrownBy(() -> new PgDumpRunner(props, exec, target).dump(Path.of("x.dump")))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageStartingWith("pg_dump failed (exit 1)")
                .hasMessageContaining("password authentication failed")
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain(PASSWORD).doesNotContain(HOST).doesNotContain(USER).doesNotContain("52.1.2.3"));
    }

    @Test
    void timeoutAndMissingTool_giveClearMessages() {

        FakeExecutor exec = new FakeExecutor();
        exec.failure = new CommandExecutor.CommandTimeoutException("timed out after 15 min");
        assertThatThrownBy(() -> new PgDumpRunner(props, exec, target).dump(Path.of("x.dump")))
                .isInstanceOf(BackupFailedException.class).hasMessage("pg_dump timed out after 15 min and was stopped.");

        exec.failure = new IOException("Cannot run program \"pg_dump\": error=2, No such file or directory");
        assertThatThrownBy(() -> new PgDumpRunner(props, exec, target).dump(Path.of("x.dump")))
                .isInstanceOf(BackupFailedException.class).hasMessageContaining("could not be started");
    }

    @Test
    void verify_readsTheArchiveTableOfContents_andReportsTablesAndVersions() throws Exception {

        FakeExecutor exec = new FakeExecutor();
        exec.result = new CommandExecutor.CommandResult(0, GOOD_LIST, "");

        PgDumpRunner.ArchiveInfo info = new PgDumpRunner(props, exec, target).verify(Path.of("x.dump"));

        assertThat(exec.commands.get(0)).startsWith("pg_restore", "--list");
        assertThat(exec.envs.get(0)).doesNotContainKey("PGPASSWORD"); // reading a file needs no database
        assertThat(info.tableCount()).isEqualTo(3);
        assertThat(info.tables()).contains("public.employees", "public.flyway_schema_history", "public.leads");
        assertThat(info.serverVersion()).isEqualTo("17.4");
        assertThat(info.pgDumpVersion()).startsWith("17.6");
    }

    @Test
    void verify_rejectsAnUnreadableOrTruncatedArchive() {

        FakeExecutor exec = new FakeExecutor();
        exec.result = new CommandExecutor.CommandResult(1, "",
                "pg_restore: error: could not read from input file: end of file");

        assertThatThrownBy(() -> new PgDumpRunner(props, exec, target).verify(Path.of("x.dump")))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageContaining("incomplete or unreadable")
                .hasMessageContaining("end of file");
    }

    @Test
    void verify_rejectsAnArchiveWithoutTheRequiredTables() {

        FakeExecutor exec = new FakeExecutor();
        exec.result = new CommandExecutor.CommandResult(0,
                ";     Dumped from database version: 17.4\n3403; 0 16410 TABLE DATA public leads postgres\n", "");

        assertThatThrownBy(() -> new PgDumpRunner(props, exec, target).verify(Path.of("x.dump")))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageContaining("no data for table \"employees\"");
    }
}
