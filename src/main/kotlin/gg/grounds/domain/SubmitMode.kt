package gg.grounds.domain

/**
 * How a submitted score combines with the one already on the board.
 *
 * A domain type rather than the proto enum: the meaning belongs to the leaderboard, not to a
 * transport, and both the REST resource and the gRPC adapter translate into it.
 */
enum class SubmitMode {
    /** Overwrite the player's score. */
    REPLACE,
    /** Add to the player's score — playtime, win counts. */
    ACCUMULATE,
    /** Keep the higher of the two — personal bests. */
    MAX,
}
