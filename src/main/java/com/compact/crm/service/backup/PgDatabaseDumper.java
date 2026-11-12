package com.compact.crm.service.backup;

import java.nio.file.Path;

// DatabaseDumper for the application's own datasource. The JDBC URL is parsed
// on each use rather than at startup, so a non-PostgreSQL datasource (the H2
// test database) only fails a backup, never the application start.
public class PgDatabaseDumper implements DatabaseDumper {

    private final BackupProperties properties;
    private final CommandExecutor executor;
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public PgDatabaseDumper(BackupProperties properties, CommandExecutor executor,
                            String jdbcUrl, String username, String password) {
        this.properties = properties;
        this.executor = executor;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    private PgDumpRunner runner() {
        return new PgDumpRunner(properties, executor, PostgresTarget.fromJdbc(jdbcUrl, username, password));
    }

    @Override
    public void dump(Path archive) throws InterruptedException {
        runner().dump(archive);
    }

    @Override
    public PgDumpRunner.ArchiveInfo verify(Path archive) throws InterruptedException {
        return runner().verify(archive);
    }
}
