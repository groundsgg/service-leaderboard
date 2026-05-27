package gg.grounds.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.quarkus.grpc.GlobalInterceptor
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.net.URI
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Server-side gRPC interceptor that:
 * 1. Reads the `authorization: Bearer <jwt>` metadata header
 * 2. Verifies the JWT against the configured JWKS endpoint (the k8s API-server's `/openid/v1/jwks`
 *    by default)
 * 3. Enforces audience = `grounds-services`
 * 4. Stashes the verified claims in a gRPC Context so service code can look up the caller via
 *    `AuthContext.current()`
 * 5. Rejects unauthenticated / invalid-token calls with Status.UNAUTHENTICATED
 *
 * Closes the SDK→service auth loop: library-grpc-contracts-sdk attaches the projected
 * ServiceAccount JWT on every call; this is the matching verification.
 *
 * Configuration (application.properties):
 *
 *     grounds.auth.jwks-url=https://kubernetes.default.svc/openid/v1/jwks
 *     grounds.auth.expected-audience=grounds-services
 *     grounds.auth.enabled=true
 *
 * Set `grounds.auth.enabled=false` for local dev where the SDK isn't attaching a token (e.g.
 * `quarkusDev` with no projected volume). In that mode the interceptor is a no-op.
 */
@ApplicationScoped
@GlobalInterceptor
class GroundsAuthInterceptor
@Inject
constructor(
    @param:ConfigProperty(name = "grounds.auth.enabled", defaultValue = "true")
    private val enabled: Boolean,
    @param:ConfigProperty(name = "grounds.auth.jwks-url") private val jwksUrl: String,
    @param:ConfigProperty(
        name = "grounds.auth.expected-audience",
        defaultValue = "grounds-services",
    )
    private val expectedAudience: String,
) : ServerInterceptor {

    @Volatile private var jwtProcessor: DefaultJWTProcessor<SecurityContext>? = null

    @PostConstruct
    fun init() {
        if (!enabled) {
            LOG.warn(
                "Grounds auth disabled — gRPC calls will be processed without JWT verification"
            )
            return
        }
        jwtProcessor =
            DefaultJWTProcessor<SecurityContext>().apply {
                val source =
                    JWKSourceBuilder.create<SecurityContext>(URI.create(jwksUrl).toURL()).build()
                jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, source)
                // Audience claim is required + enforced. Issuer is left
                // permissive because k8s SA-token issuers differ between
                // clusters (e.g. https://kubernetes.default.svc.cluster.local
                // vs https://oidc-discovery.<cluster>...). Audience binds the
                // token to *this* service-class, which is the actual abuse
                // surface.
                jwtClaimsSetVerifier =
                    DefaultJWTClaimsVerifier<SecurityContext>(
                        JWTClaimsSet.Builder().audience(expectedAudience).build(),
                        setOf("sub", "exp"),
                    )
            }
        LOG.infof("Grounds auth enabled (jwks=%s, audience=%s)", jwksUrl, expectedAudience)
    }

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        if (!enabled) {
            return next.startCall(call, headers)
        }

        val authHeader = headers.get(AUTHORIZATION)
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            call.close(
                Status.UNAUTHENTICATED.withDescription("missing or malformed Authorization header"),
                Metadata(),
            )
            return NOOP_LISTENER as ServerCall.Listener<ReqT>
        }

        val token = authHeader.removePrefix("Bearer ").trim()
        val claims =
            try {
                jwtProcessor!!.process(token, null)
            } catch (e: Exception) {
                LOG.debugf("JWT verification failed: %s", e.message)
                call.close(
                    Status.UNAUTHENTICATED.withDescription("invalid token: ${e.message}"),
                    Metadata(),
                )
                return NOOP_LISTENER as ServerCall.Listener<ReqT>
            }

        val ctx = Context.current().withValue(AuthContext.KEY, AuthClaims.from(claims))
        return Contexts.interceptCall(ctx, call, headers, next)
    }

    companion object {
        private val LOG = Logger.getLogger(GroundsAuthInterceptor::class.java)

        internal val AUTHORIZATION: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

        private val NOOP_LISTENER = object : ServerCall.Listener<Any>() {}
    }
}
