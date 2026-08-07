package gg.grounds.rest

import jakarta.ws.rs.ApplicationPath
import jakarta.ws.rs.core.Application
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType
import org.eclipse.microprofile.openapi.annotations.info.Info
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme

@ApplicationPath("/")
@OpenAPIDefinition(
    info =
        Info(
            title = "Leaderboard API",
            version = "1.0.0",
            description =
                "Persistent player rankings. A board is a (boardId, seasonId) pair: the board " +
                    "id is stable per gamemode (`bedwars.solos`), the season rolls over on " +
                    "ops's schedule and the scores from the closed season are archived rather " +
                    "than deleted.\n\n" +
                    "Scores are submitted by whoever owns the result — service-match after a " +
                    "rated match, not the gamemode — and read by anything that renders a " +
                    "board. A submission says how it combines with what is stored: overwrite, " +
                    "add, or keep the better of the two.\n\n" +
                    "A retried submission is the hazard this API is shaped around: a " +
                    "double-counted score is visible to players and stays wrong for a season. " +
                    "Send an idempotency key and a retry costs nothing.",
        )
)
@SecurityScheme(
    securitySchemeName = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
    description =
        "The projected ServiceAccount token from /var/run/secrets/grounds/token, with the " +
            "grounds-services audience.",
)
class OpenApiConfiguration : Application()
