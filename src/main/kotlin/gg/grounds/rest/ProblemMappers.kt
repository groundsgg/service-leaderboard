package gg.grounds.rest

import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

/** Argument validation, thrown by the resources before anything is read or written. */
class InvalidRequestException(message: String) : RuntimeException(message)

@Provider
class InvalidRequestMapper : ExceptionMapper<InvalidRequestException> {
    override fun toResponse(exception: InvalidRequestException): Response =
        problem(400, "Invalid request", exception.message, "invalid_request")
}

/**
 * The caller is authenticated but is not an admin.
 *
 * 403 rather than 404: hiding the endpoint from a valid workload token buys nothing — every caller
 * is in-cluster and can read the OpenAPI document — and an operator whose SA is simply missing the
 * grant deserves to be told so.
 */
class ForbiddenException(message: String) : RuntimeException(message)

@Provider
class ForbiddenMapper : ExceptionMapper<ForbiddenException> {
    override fun toResponse(exception: ForbiddenException): Response =
        problem(403, "Forbidden", exception.message, "forbidden")
}

/** A board the player has no entry on. */
class NotRankedException(message: String) : RuntimeException(message)

@Provider
class NotRankedMapper : ExceptionMapper<NotRankedException> {
    override fun toResponse(exception: NotRankedException): Response =
        problem(404, "Not ranked", exception.message, "not_ranked")
}

internal fun problem(status: Int, title: String, detail: String?, code: String): Response =
    Response.status(status)
        .type(ProblemDetails.PROBLEM_JSON)
        .entity(ProblemDetails(title = title, status = status, detail = detail, code = code))
        .build()
