package com.compact.crm.service.backup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Runs pg_dump against the CRM database and checks the archive with
// pg_restore --list. Read-only by construction: pg_dump runs its whole dump in
// one REPEATABLE READ, READ ONLY transaction and the command line contains no
// option that writes to the database (no --clean, no restore). The password
// reaches pg_dump only through the PGPASSWORD environment variable - never the
// command line, which other processes can read - and every error message is
// scrubbed before it leaves this class.
public class PgDumpRunner {

    // "3456; 0 16390 TABLE DATA public employees postgres"
    private static final Pattern TABLE_DATA = Pattern.compile("^\\d+;\\s+\\d+\\s+\\d+\\s+TABLE DATA\\s+(\\S+)\\s+(\\S+)", Pattern.MULTILINE);
    private static final Pattern DUMPED_FROM = Pattern.compile("^;\\s+Dumped from database version:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern DUMPED_BY = Pattern.compile("^;\\s+Dumped by pg_dump version:\\s*(.+)$", Pattern.MULTILINE);

    static final String SCHEMA = "public";

    private final BackupProperties properties;
    private final CommandExecutor executor;
    private final PostgresTarget target;
    private final SecretScrubber scrubber;

    public PgDumpRunner(BackupProperties properties, CommandExecutor executor, PostgresTarget target) {
        this.properties = properties;
        this.executor = executor;
        this.target = target;
        this.scrubber = new SecretScrubber(target.password(), target.host(), target.username(), target.database());
    }

    public record ArchiveInfo(int tableCount, Set<String> tables, String serverVersion, String pgDumpVersion) {
    }

    // The exact pg_dump arguments (without the password, which is never an argument).
    List<String> dumpCommand(Path archive) {
        List<String> cmd = new ArrayList<>();
        cmd.add(properties.getPgDumpPath());
        cmd.add("--format=custom");
        cmd.add("--schema=" + SCHEMA);
        cmd.add("--no-owner");
        cmd.add("--no-privileges");
        cmd.add("--no-password");
        cmd.add("--lock-wait-timeout=30000");
        cmd.add("--host=" + target.host());
        cmd.add("--port=" + target.port());
        cmd.add("--username=" + target.username());
        cmd.add("--dbname=" + target.database());
        cmd.add("--file=" + archive.toAbsolutePath());
        return cmd;
    }

    Map<String, String> environment() {
        Map<String, String> env = new HashMap<>();
        env.put("PGPASSWORD", target.password() == null ? "" : target.password());
        env.put("PGSSLMODE", sslMode());
        env.put("PGCONNECT_TIMEOUT", "15");
        env.put("PGAPPNAME", "crm-backup");
        return env;
    }

    String sslMode() {
        if (properties.getSslMode() != null && !properties.getSslMode().isBlank()) {
            return properties.getSslMode().trim();
        }
        if (target.urlSslMode() != null && !target.urlSslMode().isBlank()) {
            return target.urlSslMode().trim();
        }
        return target.isLocal() ? "prefer" : "require";
    }

    public void dump(Path archive) throws InterruptedException {
        CommandExecutor.CommandResult result = run("pg_dump", dumpCommand(archive), environment(), properties.getDumpTimeout());
        if (result.exitCode() != 0) {
            throw failure("pg_dump failed (exit " + result.exitCode() + ")", result.stderr());
        }
    }

    public ArchiveInfo verify(Path archive) throws InterruptedException {
        List<String> cmd = List.of(properties.getPgRestorePath(), "--list", archive.toAbsolutePath().toString());
        CommandExecutor.CommandResult result = run("pg_restore", cmd, Map.of(), properties.getVerifyTimeout());
        if (result.exitCode() != 0) {
            throw failure("The backup file is incomplete or unreadable (pg_restore --list exit " + result.exitCode() + ")",
                    result.stderr());
        }
        ArchiveInfo info = parseList(result.stdout());
        for (String required : properties.getRequiredTables()) {
            if (!info.tables().contains(SCHEMA + "." + required)) {
                throw new BackupFailedException("The backup file is incomplete: it has no data for table \"" + required + "\".");
            }
        }
        return info;
    }

    static ArchiveInfo parseList(String list) {
        Set<String> tables = new LinkedHashSet<>();
        Matcher m = TABLE_DATA.matcher(list);
        while (m.find()) {
            tables.add(m.group(1) + "." + m.group(2));
        }
        return new ArchiveInfo(tables.size(), tables, first(DUMPED_FROM, list), first(DUMPED_BY, list));
    }

    private static String first(Pattern p, String text) {
        Matcher m = p.matcher(text);
        if (!m.find()) {
            return null;
        }
        String v = m.group(1).trim();
        return v.length() <= 60 ? v : v.substring(0, 60);
    }

    private CommandExecutor.CommandResult run(String tool, List<String> cmd, Map<String, String> env, java.time.Duration timeout)
            throws InterruptedException {
        try {
            return executor.run(cmd, env, timeout);
        } catch (CommandExecutor.CommandTimeoutException e) {
            throw new BackupFailedException(tool + " " + e.getMessage() + " and was stopped.");
        } catch (IOException e) {
            // typically "Cannot run program": the tool is not installed or not on PATH
            throw new BackupFailedException(tool + " could not be started. Is the PostgreSQL client installed on the server?");
        }
    }

    private BackupFailedException failure(String prefix, String stderr) {
        String detail = scrubber.scrub(stderr);
        return new BackupFailedException(detail.isEmpty() ? prefix + "." : prefix + ": " + detail);
    }

    SecretScrubber scrubber() {
        return scrubber;
    }
}
