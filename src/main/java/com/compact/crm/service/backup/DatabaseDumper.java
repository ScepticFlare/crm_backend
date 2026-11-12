package com.compact.crm.service.backup;

import java.nio.file.Path;

// Writes a dump of the CRM database to a file and checks that file. The
// production implementation is PgDatabaseDumper (pg_dump + pg_restore --list).
public interface DatabaseDumper {

    void dump(Path archive) throws InterruptedException;

    PgDumpRunner.ArchiveInfo verify(Path archive) throws InterruptedException;
}
