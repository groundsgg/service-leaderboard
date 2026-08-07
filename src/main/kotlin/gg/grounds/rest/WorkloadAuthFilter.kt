package gg.grounds.rest

import gg.grounds.auth.WorkloadAuthenticator
import jakarta.annotation.Priority
import jakarta.inject.Inject
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.SecurityContext
import jakarta.ws.rs.ext.Provider
import java.security.Principal
import org.jboss.logging.Logger

/**
 * Requires a valid workload token on every leaderboard endpoint and hands the caller's identity to
 * the resources as the request's [SecurityContext] — which is what the admin check on a season
 * reset reads.
 *
 * Guarding by path prefix rather than per resource means there is no endpoint that can be reached
 * by forgetting an annotation: a new resource under `/v1/leaderboards` is covered the moment it is
 * mapped. Health and metrics sit outside the prefix and stay open, which is what scrapes and probes
 * need.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
class WorkloadAuthFilter @Inject constructor(private val authenticator: WorkloadAuthenticator) :
    ContainerRequestFilter {

    override fun filter(requestContext: ContainerRequestContext) {
        val path = requestContext.uriInfo.path
        if (!path.startsWith(GUARDED_PREFIX) && !path.startsWith("/$GUARDED_PREFIX")) return

        if (!authenticator.enabled) {
            // Local runs and tests, where no kubelet projects a token. Never true in-cluster.
            requestContext.securityContext = identity(LOCAL_DEVELOPMENT_SUBJECT)
            return
        }

        val header = requestContext.getHeaderString("Authorization")
        val token = header?.removePrefix("Bearer ")?.trim()
        if (header == null || !header.startsWith("Bearer ") || token.isNullOrEmpty()) {
            requestContext.abortWith(
                problem(401, "Unauthenticated", "Authentication is required.", "unauthenticated")
            )
            return
        }

        val claims =
            try {
                authenticator.authenticate(token)
            } catch (unavailable: WorkloadAuthenticator.VerificationUnavailableException) {
                // The key set could not be fetched. That is our problem, not the caller's: a 401
                // here tells a correctly-credentialled caller to stop retrying.
                LOG.warnf(unavailable, "Token verification unavailable")
                requestContext.abortWith(
                    problem(
                        503,
                        "Service unavailable",
                        "Cannot verify credentials right now.",
                        "verification_unavailable",
                    )
                )
                return
            }

        if (claims == null) {
            requestContext.abortWith(
                problem(401, "Unauthenticated", "Credentials are not valid.", "unauthenticated")
            )
            return
        }
        requestContext.securityContext = identity(claims.subject)
    }

    private fun identity(username: String): SecurityContext =
        object : SecurityContext {
            override fun getUserPrincipal(): Principal = Principal { username }

            override fun isUserInRole(role: String): Boolean = false

            override fun isSecure(): Boolean = true

            override fun getAuthenticationScheme(): String = "Bearer"
        }

    companion object {
        private val LOG = Logger.getLogger(WorkloadAuthFilter::class.java)
        private const val GUARDED_PREFIX = "v1/leaderboards"

        /**
         * The subject a request carries when auth is off. Deliberately not admin-shaped: a local
         * run that lets everyone through must not also silently grant season resets, or the admin
         * check is never exercised until production.
         */
        const val LOCAL_DEVELOPMENT_SUBJECT: String = "local-development"
    }
}
