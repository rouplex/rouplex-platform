package org.rouplex.commons.security;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class SecurityUtils {

    private static final TrustManager trustAllCerts = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    /**
     * Convenience method for building client side {@link SSLContext} instances that do not provide a client identity
     * and accept any server identity.
     *
     * @return
     *          A relaxed sslContext
     */
    public static SSLContext buildRelaxedSSLContext(boolean skipHostNameVerification, boolean skipCertVerification) {
        // todo implement skipHostNameVerification
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            TrustManager[] trustManagers = skipCertVerification ? new TrustManager[] {trustAllCerts} : null /* default */;
            sslContext.init(null, trustManagers, null);
            return sslContext;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
