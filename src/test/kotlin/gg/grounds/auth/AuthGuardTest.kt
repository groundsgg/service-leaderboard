package gg.grounds.auth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthGuardTest {

    @Test
    fun `platform-admin subject is admin`() {
        assertTrue(AuthGuard.isAdminSubject("system:serviceaccount:platform-admin:platform-admin"))
    }

    @Test
    fun `leaderboard-admin subject is admin`() {
        assertTrue(AuthGuard.isAdminSubject("system:serviceaccount:api:leaderboard-admin"))
    }

    @Test
    fun `default SA is not admin`() {
        assertFalse(AuthGuard.isAdminSubject("system:serviceaccount:user-hendrik:default"))
    }

    @Test
    fun `arbitrary plugin SA is not admin`() {
        assertFalse(
            AuthGuard.isAdminSubject("system:serviceaccount:user-hendrik:sample-leaderboard-plugin")
        )
    }

    @Test
    fun `empty subject is not admin`() {
        assertFalse(AuthGuard.isAdminSubject(""))
    }

    @Test
    fun `subject that contains admin as substring but doesnt suffix-match is not admin`() {
        // Defense against accidentally permissive prefix matches.
        assertFalse(AuthGuard.isAdminSubject("system:serviceaccount:platform-admin:default"))
    }
}
