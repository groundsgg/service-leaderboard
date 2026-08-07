package gg.grounds.rest

import org.eclipse.microprofile.openapi.annotations.media.Schema

/**
 * The one error shape this API returns, modelled on RFC 9457 problem details.
 *
 * [code] is what a caller should branch on. The title and detail are for whoever is reading a log;
 * they are prose and may be reworded, which is exactly why they are not the contract.
 */
@Schema(name = "Problem", description = "A failed request, in RFC 9457 problem-details form.")
data class ProblemDetails(
    @get:Schema(description = "Short, human-readable summary.", examples = ["Not ranked"])
    val title: String,
    @get:Schema(description = "HTTP status, repeated in the body.", examples = ["404"])
    val status: Int,
    @get:Schema(
        description = "What went wrong with this specific request.",
        examples = ["The player has no entry on this board for season s0."],
    )
    val detail: String?,
    @get:Schema(
        description = "Stable machine-readable code. Branch on this, not on the prose.",
        examples = ["not_ranked", "invalid_request", "unauthenticated", "forbidden"],
    )
    val code: String,
) {
    companion object {
        const val PROBLEM_JSON: String = "application/problem+json"
    }
}
