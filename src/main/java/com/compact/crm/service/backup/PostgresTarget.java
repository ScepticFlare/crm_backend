package com.compact.crm.service.backup;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

// The database pg_dump must read, derived from the application's own
// datasource (spring.datasource.url / username / password) so a backup can
// only ever target the CRM's database. Only a plain single-host
// jdbc:postgresql://host[:port]/database[?params] URL is accepted; anything
// else (another driver, several hosts, odd characters) is refused rather than
// guessed at. toString() never includes the host, user or password.
public record PostgresTarget(String host, int port, String database, String username, String password, String urlSslMode) {

    private static final String PREFIX = "jdbc:postgresql://";
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}|\\[[0-9A-Fa-f:.]{2,45}]");
    private static final Pattern DATABASE = Pattern.compile("[A-Za-z0-9_.-]{1,63}");
    private static final Pattern USER = Pattern.compile("[A-Za-z0-9_.@-]{1,128}");

    public static PostgresTarget fromJdbc(String jdbcUrl, String username, String password) {

        if (jdbcUrl == null || !jdbcUrl.startsWith(PREFIX)) {
            throw new BackupFailedException("The CRM database is not a PostgreSQL database; only PostgreSQL can be backed up.");
        }

        String rest = jdbcUrl.substring(PREFIX.length());
        int query = rest.indexOf('?');
        Map<String, String> params = query >= 0 ? parseQuery(rest.substring(query + 1)) : Map.of();
        String authority = query >= 0 ? rest.substring(0, query) : rest;

        int slash = authority.indexOf('/');
        if (slash <= 0) {
            throw invalid();
        }
        String hostPort = authority.substring(0, slash);
        String database = decode(authority.substring(slash + 1));

        if (hostPort.contains(",") || hostPort.contains("@")) {
            throw invalid();
        }

        String host;
        int port = 5432;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            if (close < 0) {
                throw invalid();
            }
            host = hostPort.substring(0, close + 1);
            String tail = hostPort.substring(close + 1);
            if (!tail.isEmpty()) {
                port = parsePort(tail.startsWith(":") ? tail.substring(1) : "x");
            }
        } else {
            int colon = hostPort.lastIndexOf(':');
            host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
            if (colon >= 0) {
                port = parsePort(hostPort.substring(colon + 1));
            }
        }

        String user = username != null && !username.isBlank() ? username : params.get("user");
        String pass = password != null && !password.isEmpty() ? password : params.getOrDefault("password", "");

        if (!HOST.matcher(host).matches() || !DATABASE.matcher(database).matches()
                || user == null || !USER.matcher(user).matches()) {
            throw invalid();
        }

        // libpq takes an IPv6 literal without brackets
        String libpqHost = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        return new PostgresTarget(libpqHost, port, database, user, pass, params.get("sslmode"));
    }

    public boolean isLocal() {
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
    }

    private static int parsePort(String s) {
        try {
            int p = Integer.parseInt(s);
            if (p < 1 || p > 65535) {
                throw invalid();
            }
            return p;
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(decode(pair.substring(0, eq)).toLowerCase(), decode(pair.substring(eq + 1)));
            }
        }
        return out;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static BackupFailedException invalid() {
        return new BackupFailedException("The CRM database URL has an unsupported format for backups.");
    }

    @Override
    public String toString() {
        return "PostgresTarget[port=" + port + ", database=" + database + "]";
    }
}
