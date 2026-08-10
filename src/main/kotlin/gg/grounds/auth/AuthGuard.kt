package gg.grounds.auth

/**
 * Method-ACL decisions live here. Hard-coded today because the v2.2 Service Architecture spec calls
 * for server-side hard-coded Method-ACL — central config or a Keycloak-role lookup is YAGNI until
 * we have more than a handful of restricted methods.
 *
 * Admin subjects are matched by suffix: a JWT `sub` like
 * `system:serviceaccount:<ns>:platform-admin` or `system:serviceaccount:<ns>:leaderboard-admin`
 * passes the admin check. Ops creates these SAs manually in the platform-admin namespace; their
 * existence is the grant.
 */
object AuthGuard {

    private val ADMIN_SA_SUFFIXES = listOf(":platform-admin", ":leaderboard-admin")

    /**
     * The decision, taking the subject rather than reading it out of a transport. The HTTP layer
     * asks this holding the caller's identity from the request's SecurityContext.
     */
    fun isAdminSubject(subject: String): Boolean = ADMIN_SA_SUFFIXES.any { subject.endsWith(it) }
}
