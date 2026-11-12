package com.compact.crm.service.backup;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

// Private storage for backup files. Implementations must never make a file
// publicly reachable: the only way to read one back is download(), which the
// backend calls on behalf of an authenticated ADMIN.
public interface BackupStorage {

    boolean isConfigured();

    void upload(String key, Path file) throws IOException;

    void download(String key, OutputStream out) throws IOException;

    void delete(String key) throws IOException;
}
