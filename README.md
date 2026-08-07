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

### Retiring gRPC

The `LeaderboardService` gRPC contract is still served on the same port, because
`service-match` and `plugin-match` still dial the stubs. That adapter holds no
rules of its own — both transports call the same `LeaderboardRepository` — so it
can be deleted once no caller needs it. The order matters:

1. Release this service. It now answers on both transports.
2. Move `service-match` and `plugin-match` to HTTP, and roll the proxies.
3. Delete `gg.grounds.api`, `GroundsAuthInterceptor`, `AuthContext`, the
   `quarkus-grpc` dependency and the `library-grpc-contracts-leaderboard`
   dependency.

Doing 3 before 2 stops every rated match from reaching a board, silently: the
submit is best-effort by design and its caller swallows the failure.

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
