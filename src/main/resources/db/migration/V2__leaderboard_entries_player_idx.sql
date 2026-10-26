-- Every board one player is on (GET /v1/leaderboards/players/{playerId}). The
-- primary key leads with board_id, so without this a lookup by player alone
-- scans the whole table.
CREATE INDEX IF NOT EXISTS leaderboard_entries_player_idx
    ON leaderboard_entries (player_id);
