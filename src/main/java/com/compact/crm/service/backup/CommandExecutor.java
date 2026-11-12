package com.compact.crm.service.backup;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

// Runs one external program (pg_dump / pg_restore) without a shell. An
// interface so the backup logic can be tested without PostgreSQL tools.
public interface CommandExecutor {

    // The command is passed as separate arguments (never through a shell).
    // environment is ADDED to a scrubbed copy of this process's environment
    // (inherited PG* variables are removed first). Throws
    // CommandTimeoutException after killing the process if it outlives timeout.
    CommandResult run(List<String> command, Map<String, String> environment, Duration timeout)
            throws IOException, InterruptedException;

    record CommandResult(int exitCode, String stdout, String stderr) {
    }

    class CommandTimeoutException extends IOException {
        public CommandTimeoutException(String message) {
            super(message);
        }
    }
}
