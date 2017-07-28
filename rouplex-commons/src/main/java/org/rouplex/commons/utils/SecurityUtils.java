package org.rouplex.commons.utils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * Some useful, basic functionality.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class SecurityUtils {
    public static SSLContext buildRelaxedSSLContext() throws IOException {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
                }

                public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, null);

            return sslContext;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
