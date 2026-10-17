# service-leaderboard

Quarkus service for persistent player rankings. A board is a `(boardId, seasonId)`
pair: the board id is stable per gamemode (`bedwars.solos`), the season rolls over
on ops's schedule, and the closed season's scores are archived rather than deleted.

## API

REST under `/v1/leaderboards`, documented by an OpenAPI snapshot published to
[groundsgg/api-reference](https://github.com/groundsgg/api-reference) on every
release. Rendering stays central — this service serves no Swagger UI of its own.

Generate the snapshot locally:

```bash
./gradlew generateOpenApiSnapshot   # -> build/api-reference/openapi.json
```

Callers authenticate with the projected workload token from
`/var/run/secrets/grounds/token`, sent as a bearer. It is verified against the
cluster's JWKS with the `grounds-services` audience. A season reset additionally
requires an admin ServiceAccount (`:platform-admin` or `:leaderboard-admin`).

The `LeaderboardService` gRPC adapter was removed once service-match 1.0.0 and
plugin-match 0.7.0 had moved to REST; the rules it wrapped always lived in
`LeaderboardRepository`, which the REST resource calls directly.

## Score stream

Submissions nobody waits on (service-match's rated results) can also arrive on
the `LEADERBOARD_SCORES` JetStream stream as `leaderboard.score.submitted.<boardId>`,
with the same JSON body as `POST /v1/leaderboards/{boardId}/scores`. The
`leaderboard-writer` durable applies them through the same code path, one at a
time and in order. Every message must carry an `idempotencyKey`; redeliveries
deduplicate in the database. Off unless `LEADERBOARD_CONSUMER_ENABLED=true`; the
stream and the NATS user (credentials in `NATS_URL`) come from grounds-pulumi.

Idempotency keys are remembered for `LEADERBOARD_IDEMPOTENCY_WINDOW` (8 days,
longer than the stream keeps messages) once `LEADERBOARD_IDEMPOTENCY_PRUNE_ENABLED=true`;
until then they are kept forever.

## Development

Run in dev mode with live reload:

```bash
./gradlew --console=plain quarkusDev
```

Auth is on by default and validates the caller's projected ServiceAccount token
against the cluster JWKS. Locally there is no such token — set
`GROUNDS_AUTH_ENABLED=false`. Note that this lets every caller through as
`local-development`, which is deliberately *not* an admin subject, so season
resets stay refused.

Run in dev mode with live reload using DevSpace in a Kubernetes cluster (Initial build may take some time):

```bash
devspace use namespace api
devspace dev
```

## License

Licensed under the GNU Affero General Public License v3.0
