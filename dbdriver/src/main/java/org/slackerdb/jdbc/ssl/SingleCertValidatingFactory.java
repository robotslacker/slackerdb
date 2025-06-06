/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.ssl;

import org.slackerdb.jdbc.util.GT;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.UUID;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Provides a SSLSocketFactory that authenticates the remote server against an explicit pre-shared
 * SSL certificate. This is more secure than using the NonValidatingFactory as it prevents "man in
 * the middle" attacks. It is also more secure than relying on a central CA signing your server's
 * certificate as it pins the server's certificate.
 *
 * <p>This class requires a single String parameter specified by setting the connection property
 * <code>sslfactoryarg</code>. The value of this property is the PEM-encoded remote server's SSL
 * certificate.</p>
 *
 * <p>Where the certificate is loaded from is based upon the prefix of the <code>sslfactoryarg</code> property.
 * The following table lists the valid set of prefixes.</p>
 *
 * <table border="1">
 * <caption>Valid prefixes for sslfactoryarg</caption>
 * <tr>
 *     <th>Prefix</th>
 *     <th>Example</th>
 *     <th>Explanation</th>
 * </tr>
 * <tr>
 *     <td><code>classpath:</code></td>
 *     <td><code>classpath:ssl/server.crt</code></td>
 *     <td>Loaded from the classpath.</td>
 * </tr>
 * <tr>
 *     <td><code>file:</code></td>
 *     <td><code>file:/foo/bar/server.crt</code></td>
 *     <td>Loaded from the filesystem.</td>
 * </tr>
 * <tr>
 *     <td><code>env:</code></td>
 *     <td><code>env:mydb_cert</code></td>
 *     <td>Loaded from string value of the <code>mydb_cert</code> environment variable.</td>
 * </tr>
 * <tr>
 *     <td><code>sys:</code></td>
 *     <td><code>sys:mydb_cert</code></td>
 *     <td>Loaded from string value of the <code>mydb_cert</code> system property.</td>
 * </tr>
 * <tr>
 *     <td><pre>-----BEGIN CERTIFICATE------</pre></td>
 *     <td>
 *         <pre>
 * -----BEGIN CERTIFICATE-----
 * MIIDQzCCAqygAwIBAgIJAOd1tlfiGoEoMA0GCSqGSIb3DQEBBQUAMHUxCzAJBgNV
 * [... truncated ...]
 * UCmmYqgiVkAGWRETVo+byOSDZ4swb10=
 * -----END CERTIFICATE-----
 *         </pre>
*      </td>
 *     <td>Loaded from string value of the argument.</td>
 * </tr>
 * </table>
 */

public class SingleCertValidatingFactory extends WrappedFactory {
  private static final String FILE_PREFIX = "file:";
  private static final String CLASSPATH_PREFIX = "classpath:";
  private static final String ENV_PREFIX = "env:";
  private static final String SYS_PROP_PREFIX = "sys:";

  public SingleCertValidatingFactory(String sslFactoryArg) throws GeneralSecurityException {
    if (sslFactoryArg == null || "".equals(sslFactoryArg)) {
      throw new GeneralSecurityException(GT.tr("The sslfactoryarg property may not be empty."));
    }
    InputStream in = null;
    try {
      if (sslFactoryArg.startsWith(FILE_PREFIX)) {
        String path = sslFactoryArg.substring(FILE_PREFIX.length());
        in = new BufferedInputStream(new FileInputStream(path));
      } else if (sslFactoryArg.startsWith(CLASSPATH_PREFIX)) {
        String path = sslFactoryArg.substring(CLASSPATH_PREFIX.length());
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        InputStream inputStream;
        if (classLoader != null) {
          inputStream = classLoader.getResourceAsStream(path);
          if (inputStream == null) {
            throw new IllegalArgumentException(
                GT.tr("Unable to find resource {0} via Thread contextClassLoader {1}",
                    path, classLoader)
            );
          }
        } else {
          inputStream = getClass().getResourceAsStream(path);
          if (inputStream == null) {
            throw new IllegalArgumentException(
                GT.tr("Unable to find resource {0} via class {1} ClassLoader {2}",
                    path, getClass(), getClass().getClassLoader())
            );
          }
        }
        in = new BufferedInputStream(inputStream);
      } else if (sslFactoryArg.startsWith(ENV_PREFIX)) {
        String name = sslFactoryArg.substring(ENV_PREFIX.length());
        String cert = System.getenv(name);
        if (cert == null || "".equals(cert)) {
          throw new GeneralSecurityException(GT.tr(
              "The environment variable containing the server's SSL certificate must not be empty."));
        }
        in = new ByteArrayInputStream(cert.getBytes(StandardCharsets.UTF_8));
      } else if (sslFactoryArg.startsWith(SYS_PROP_PREFIX)) {
        String name = sslFactoryArg.substring(SYS_PROP_PREFIX.length());
        String cert = System.getProperty(name);
        if (cert == null || "".equals(cert)) {
          throw new GeneralSecurityException(GT.tr(
              "The system property containing the server's SSL certificate must not be empty."));
        }
        in = new ByteArrayInputStream(cert.getBytes(StandardCharsets.UTF_8));
      } else if (sslFactoryArg.startsWith("-----BEGIN CERTIFICATE-----")) {
        in = new ByteArrayInputStream(sslFactoryArg.getBytes(StandardCharsets.UTF_8));
      } else {
        throw new GeneralSecurityException(GT.tr(
            "The sslfactoryarg property must start with the prefix file:, classpath:, env:, sys:, or -----BEGIN CERTIFICATE-----."));
      }

      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(null, new TrustManager[]{new SingleCertTrustManager(in)}, null);
      factory = ctx.getSocketFactory();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      if (e instanceof GeneralSecurityException) {
        throw (GeneralSecurityException) e;
      }
      throw new GeneralSecurityException(GT.tr("An error occurred reading the certificate"), e);
    } finally {
      if (in != null) {
        try {
          in.close();
        } catch (Exception e2) {
          // ignore
        }
      }
    }
  }

  public static class SingleCertTrustManager implements X509TrustManager {
    X509Certificate cert;
    X509TrustManager trustManager;

    public SingleCertTrustManager(InputStream in) throws IOException, GeneralSecurityException {
      KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
      // Note: KeyStore requires it be loaded even if you don't load anything into it:
      ks.load(null);
      CertificateFactory cf = CertificateFactory.getInstance("X509");
      cert = (X509Certificate) cf.generateCertificate(in);
      ks.setCertificateEntry(UUID.randomUUID().toString(), cert);
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(ks);
      for (TrustManager tm : tmf.getTrustManagers()) {
        if (tm instanceof X509TrustManager) {
          trustManager = (X509TrustManager) tm;
          break;
        }
      }
      if (trustManager == null) {
        throw new GeneralSecurityException(GT.tr("No X509TrustManager found"));
      }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
      trustManager.checkServerTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[]{cert};
    }
  }
}
