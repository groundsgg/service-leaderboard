package gg.grounds.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.KeySourceException
import com.nimbusds.jose.jwk.JWKMatcher
import com.nimbusds.jose.jwk.JWKSelector
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.Resource
import com.nimbusds.jose.util.ResourceRetriever
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import java.io.FileInputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Who is calling, established from the projected workload token the caller presents.
 *
 * Every caller is in-cluster — plugins on the proxies and game servers — and they all authenticate
 * the same way: the kubelet projects a ServiceAccount token with the `grounds-services` audience
 * into the pod, and the client sends it as a bearer.
 *
 * The token is verified against the **cluster's JWKS** rather than with a `TokenReview` call.
 * TokenReview needs cluster-scoped RBAC, which the chart cannot grant on Stage; verifying a
 * signature needs no permissions at all and costs a signature check rather than a round trip,
 * because Nimbus caches the key set.
 *
 * Both transports share this: the HTTP filter and the gRPC interceptor differ only in how they
 * report a rejection, never in what they accept.
 *
 * Configuration (application.properties):
 * ```
 * grounds.auth.enabled=true
 * grounds.auth.jwks-url=https://kubernetes.default.svc/openid/v1/jwks
 * grounds.auth.expected-audience=grounds-services
 * ```
 *
 * Set `grounds.auth.enabled=false` for local dev and tests, where no kubelet projects a token;
 * every caller is then let through unverified.
 *
 * JWKS fetch: the managed-Kubernetes `/openid/v1/jwks` endpoint needs cluster-CA trust, this pod's
 * SA-Token as a bearer, and `Accept: application/jwk-set+json` — a plain HTTPS GET fails with PKIX
 * / 403 / 406. In-cluster (the CA bundle is present) we use [K8sJwksRetriever]; locally we fall
 * back to default TLS trust.
 */
@Startup
@ApplicationScoped
class WorkloadAuthenticator(
    @param:ConfigProperty(name = "grounds.auth.enabled", defaultValue = "true")
    val enabled: Boolean,
    @param:ConfigProperty(name = "grounds.auth.jwks-url") private val jwksUrl: String,
    @param:ConfigProperty(
        name = "grounds.auth.expected-audience",
        defaultValue = "grounds-services",
    )
    private val expectedAudience: String,
    @param:ConfigProperty(
        name = "grounds.auth.k8s-ca-file",
        defaultValue = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt",
    )
    private val caFile: String,
    @param:ConfigProperty(
        name = "grounds.auth.k8s-token-file",
        defaultValue = "/var/run/secrets/kubernetes.io/serviceaccount/token",
    )
    private val tokenFile: String,
) {

    @Volatile private var processor: DefaultJWTProcessor<SecurityContext>? = null

    @PostConstruct
    fun init() {
        if (!enabled) {
            LOG.warn("Grounds auth disabled — calls are processed without JWT verification")
            return
        }
        val jwkSource =
            if (Files.exists(Path.of(caFile))) {
                // In-cluster: trust the cluster CA and authenticate the fetch with our SA-Token.
                val ssl = clusterCaSslContext(caFile)
                JWKSourceBuilder.create<SecurityContext>(
                        URI.create(jwksUrl).toURL(),
                        K8sJwksRetriever(tokenFile, ssl.socketFactory),
                    )
                    .build()
            } else {
                // Local/test: no projected SA volume — fall back to system trust, no bearer.
                LOG.warnf("cluster CA %s not found — using default TLS trust (local/test)", caFile)
                JWKSourceBuilder.create<SecurityContext>(URI.create(jwksUrl).toURL()).build()
            }
        // Fetched at startup so a broken JWKS endpoint fails the pod rather than the first login.
        jwkSource.get(JWKSelector(JWKMatcher.Builder().build()), null)
        processor =
            DefaultJWTProcessor<SecurityContext>().apply {
                jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, jwkSource)
                // Audience is required and enforced. The issuer is left permissive because
                // ServiceAccount issuers differ between clusters; audience is what binds a token to
                // this service-class rather than to any other holder of a valid cluster token.
                jwtClaimsSetVerifier =
                    DefaultJWTClaimsVerifier<SecurityContext>(
                        JWTClaimsSet.Builder().audience(expectedAudience).build(),
                        setOf("sub", "exp"),
                    )
            }
        LOG.infof("Grounds auth enabled (jwks=%s, audience=%s)", jwksUrl, expectedAudience)
    }

    /**
     * Returns the caller's claims, or null when the token is not a valid credential for this
     * service.
     *
     * Throws [VerificationUnavailableException] when the key set cannot be fetched. That is
     * deliberately distinct from "invalid": a caller told its credentials are wrong stops retrying,
     * which turns a moment without keys into an outage.
     */
    fun authenticate(token: String): AuthClaims? {
        val current = processor ?: throw VerificationUnavailableException(null)
        return try {
            AuthClaims.from(current.process(token, null))
        } catch (error: KeySourceException) {
            throw VerificationUnavailableException(error)
        } catch (error: Exception) {
            LOG.debugf("Token rejected: %s", error.message)
            null
        }
    }

    /** Builds an [SSLContext] trusting only the cluster CA bundle at [caFile]. */
    private fun clusterCaSslContext(caFile: String): SSLContext {
        val certs =
            FileInputStream(caFile).use {
                CertificateFactory.getInstance("X.509").generateCertificates(it)
            }
        val ks =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                certs.forEachIndexed { i, c -> setCertificateEntry("k8s-ca-$i", c) }
            }
        val tmf =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(ks)
            }
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    }

    class VerificationUnavailableException(cause: Throwable?) :
        RuntimeException("cannot verify credentials", cause)

    companion object {
        private val LOG = Logger.getLogger(WorkloadAuthenticator::class.java)
    }
}

/**
 * Fetches the cluster's JWKS: trusts the cluster CA and sends this pod's own token as a bearer,
 * re-read per fetch because bound tokens rotate. Nimbus caches the key set, so this runs on warmup
 * and the rare refresh rather than per request.
 */
private class K8sJwksRetriever(
    private val tokenFile: String,
    private val socketFactory: SSLSocketFactory,
) : ResourceRetriever {
    override fun retrieveResource(url: URL): Resource {
        val token = Files.readString(Path.of(tokenFile)).trim()
        val conn =
            (url.openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = socketFactory
                setRequestProperty("Authorization", "Bearer $token")
                // The k8s OIDC JWKS endpoint only serves application/jwk-set+json and 406s on
                // application/json. Nimbus parses the body as JSON regardless of content-type.
                setRequestProperty("Accept", "application/jwk-set+json")
                connectTimeout = 1500
                readTimeout = 1500
            }
        val code = conn.responseCode
        if (code != 200) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IOException("JWKS fetch HTTP $code: ${err.take(200)}")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        return Resource(body, conn.contentType)
    }
}
