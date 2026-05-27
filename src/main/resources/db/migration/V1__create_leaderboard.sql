-- Leaderboard schema: per (board_id, season_id, player_id) score row.
-- Composite primary key keeps SubmitScore O(1) by board + player.
-- The descending (board_id, season_id, score) index drives GetTop +
-- rank computation; Postgres uses index-only scan when projecting just
-- (player_id, score).

CREATE TABLE IF NOT EXISTS leaderboard_entries (
    board_id      TEXT          NOT NULL,
    season_id     TEXT          NOT NULL,
    player_id     UUID          NOT NULL,
    score         BIGINT        NOT NULL,
    last_updated  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (board_id, season_id, player_id)
);

CREATE INDEX IF NOT EXISTS leaderboard_entries_topn_idx
    ON leaderboard_entries (board_id, season_id, score DESC, last_updated);

-- The active season per board. SeasonReset rolls this forward.
-- Empty / missing rows are treated as season "" (single global season),
-- which is the default until ops calls SeasonReset for the first time.
CREATE TABLE IF NOT EXISTS leaderboard_active_seasons (
    board_id      TEXT  PRIMARY KEY,
    season_id     TEXT  NOT NULL,
    rolled_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Idempotency ledger for SubmitScore. PK ensures double-submit under
-- the same idempotency_key collapses to one effective write.
-- Pruned by a background job once entries age past the idempotency
-- window (configured in application.properties).
CREATE TABLE IF NOT EXISTS leaderboard_submit_idempotency (
    idempotency_key  TEXT         PRIMARY KEY,
    board_id         TEXT         NOT NULL,
    player_id        UUID         NOT NULL,
    effective_score  BIGINT       NOT NULL,
    seen_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS leaderboard_submit_idempotency_seen_idx
    ON leaderboard_submit_idempotency (seen_at);
