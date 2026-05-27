package gg.grounds.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuthGuardTest {

    @Test
    fun `platform-admin subject is admin`() {
        assertThat(AuthGuard.isAdminSubject("system:serviceaccount:platform-admin:platform-admin"))
            .isTrue()
    }

    @Test
    fun `leaderboard-admin subject is admin`() {
        assertThat(AuthGuard.isAdminSubject("system:serviceaccount:api:leaderboard-admin")).isTrue()
    }

    @Test
    fun `default SA is not admin`() {
        assertThat(AuthGuard.isAdminSubject("system:serviceaccount:user-hendrik:default")).isFalse()
    }

    @Test
    fun `arbitrary plugin SA is not admin`() {
        assertThat(
                AuthGuard.isAdminSubject(
                    "system:serviceaccount:user-hendrik:sample-leaderboard-plugin"
                )
            )
            .isFalse()
    }

    @Test
    fun `empty subject is not admin`() {
        assertThat(AuthGuard.isAdminSubject("")).isFalse()
    }

    @Test
    fun `subject that contains admin as substring but doesnt suffix-match is not admin`() {
        // Defense against accidentally permissive prefix matches.
        assertThat(AuthGuard.isAdminSubject("system:serviceaccount:platform-admin:default"))
            .isFalse()
    }
}
