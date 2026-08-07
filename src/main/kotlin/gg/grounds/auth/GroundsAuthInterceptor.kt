package gg.grounds.auth

import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.quarkus.grpc.GlobalInterceptor
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * Reads `authorization: Bearer <jwt>` off an incoming gRPC call, hands it to
 * [WorkloadAuthenticator], and stashes the verified claims in a gRPC Context so service code can
 * look the caller up via [AuthContext.current].
 *
 * The rules for what counts as a valid credential live in the authenticator, shared with the HTTP
 * filter; this only translates a verdict into a gRPC status. It goes when the gRPC surface does.
 */
@ApplicationScoped
@GlobalInterceptor
class GroundsAuthInterceptor @Inject constructor(private val authenticator: WorkloadAuthenticator) :
    ServerInterceptor {

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        if (!authenticator.enabled) {
            return next.startCall(call, headers)
        }

        val authHeader = headers.get(AUTHORIZATION)
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return reject(call, Status.UNAUTHENTICATED, "missing or malformed Authorization header")
        }

        val claims =
            try {
                authenticator.authenticate(authHeader.removePrefix("Bearer ").trim())
            } catch (unavailable: WorkloadAuthenticator.VerificationUnavailableException) {
                // The key set could not be fetched. UNAUTHENTICATED would tell a correctly
                // credentialled caller to stop retrying; UNAVAILABLE says "ask again".
                LOG.warnf(unavailable, "Token verification unavailable")
                return reject(call, Status.UNAVAILABLE, "cannot verify credentials right now")
            } ?: return reject(call, Status.UNAUTHENTICATED, "invalid token")

        val ctx = Context.current().withValue(AuthContext.KEY, claims)
        return Contexts.interceptCall(ctx, call, headers, next)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <ReqT, RespT> reject(
        call: ServerCall<ReqT, RespT>,
        status: Status,
        description: String,
    ): ServerCall.Listener<ReqT> {
        call.close(status.withDescription(description), Metadata())
        return NOOP_LISTENER as ServerCall.Listener<ReqT>
    }

    companion object {
        private val LOG = Logger.getLogger(GroundsAuthInterceptor::class.java)

        internal val AUTHORIZATION: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

        private val NOOP_LISTENER = object : ServerCall.Listener<Any>() {}
    }
}
