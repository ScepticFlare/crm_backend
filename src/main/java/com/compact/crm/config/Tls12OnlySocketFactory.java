package com.compact.crm.config;

import org.postgresql.ssl.LibPQFactory;
import org.postgresql.ssl.WrappedFactory;
import org.postgresql.util.PSQLException;

import java.io.IOException;
import java.net.Socket;
import java.util.Properties;

import javax.net.ssl.SSLSocket;

// TEMPORARY DIAGNOSTIC (see pgjdbc 42.7.12 MakeSSL.convert -> SSLSocket.startHandshake failing
// with "SSL error: Broken pipe" against Supabase's Session Pooler on TLSv1.3). Wraps the driver's
// own default sslfactory (LibPQFactory - same trust/validation behavior as sslmode=require today,
// deliberately NOT changed here) and forces TLSv1.2 on the socket pgjdbc hands to
// startHandshake(), to isolate whether the failure is specific to the JVM's TLSv1.3 client
// implementation against this pooler. Activated only via sslfactory=<this class> on
// SPRING_DATASOURCE_URL; remove this class and that query param once the experiment is done.
public class Tls12OnlySocketFactory extends WrappedFactory {

  public Tls12OnlySocketFactory(Properties info) throws PSQLException {
    this.factory = new LibPQFactory(info);
  }

  @Override
  public Socket createSocket(Socket socket, String host, int port, boolean autoClose)
      throws IOException {
    SSLSocket sslSocket = (SSLSocket) factory.createSocket(socket, host, port, autoClose);
    sslSocket.setEnabledProtocols(new String[] {"TLSv1.2"});
    return sslSocket;
  }
}
