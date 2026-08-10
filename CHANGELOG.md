# Changelog

## [2.0.0](https://github.com/groundsgg/service-leaderboard/compare/v1.3.0...v2.0.0) (2026-08-10)


### ⚠ BREAKING CHANGES

* **api:** drop the LeaderboardService gRPC adapter ([#16](https://github.com/groundsgg/service-leaderboard/issues/16))

### Features

* **api:** drop the LeaderboardService gRPC adapter ([#16](https://github.com/groundsgg/service-leaderboard/issues/16)) ([fe511ae](https://github.com/groundsgg/service-leaderboard/commit/fe511aefa42eba07ba3f065885c876f76d6cf9e1))

## [1.3.0](https://github.com/groundsgg/service-leaderboard/compare/v1.2.0...v1.3.0) (2026-08-07)


### Features

* **api:** serve the leaderboard over REST alongside gRPC ([#14](https://github.com/groundsgg/service-leaderboard/issues/14)) ([bb37cd9](https://github.com/groundsgg/service-leaderboard/commit/bb37cd9de99c1dcb540919d0f191173ba4a9d722))

## [1.2.0](https://github.com/groundsgg/service-leaderboard/compare/v1.1.0...v1.2.0) (2026-08-04)


### Features

* **metrics:** expose JVM, HTTP and connection-pool metrics ([#13](https://github.com/groundsgg/service-leaderboard/issues/13)) ([c9caeda](https://github.com/groundsgg/service-leaderboard/commit/c9caedaa9addaf21c9c63e4b554aefede8ddf8fe))


### Bug Fixes

* **auth:** JWKS fetch trusts cluster CA + sends SA-bearer (OVH-MKS) ([#11](https://github.com/groundsgg/service-leaderboard/issues/11)) ([8302fce](https://github.com/groundsgg/service-leaderboard/commit/8302fce6f71ffed6fa4dea1a5946394cf2060649))

## [1.1.0](https://github.com/groundsgg/service-leaderboard/compare/v1.0.0...v1.1.0) (2026-05-28)


### Features

* **otel:** server-side traces to Alloy ([#8](https://github.com/groundsgg/service-leaderboard/issues/8)) ([6c163de](https://github.com/groundsgg/service-leaderboard/commit/6c163de8ea28e47855a724e166e951ec8623a2c6))

## 1.0.0 (2026-05-27)


### Features

* **auth:** admin-only Method-ACL for SeasonReset ([#7](https://github.com/groundsgg/service-leaderboard/issues/7)) ([439b03c](https://github.com/groundsgg/service-leaderboard/commit/439b03cffa88c5c2487fc9a1c1cea6ab9d8fb484))
* **auth:** JWT validation interceptor for incoming gRPC calls ([#6](https://github.com/groundsgg/service-leaderboard/issues/6)) ([1e7a90c](https://github.com/groundsgg/service-leaderboard/commit/1e7a90c869a88cda87a2b6bd241b7486ee5bd035))
* initial service-leaderboard scaffold (S2 vertical slice) ([3a606fc](https://github.com/groundsgg/service-leaderboard/commit/3a606fcaa461ab0f3994d70f1da4b2d70122e369))


### Bug Fixes

* actually replace service-player references with service-leaderboard ([428bd0a](https://github.com/groundsgg/service-leaderboard/commit/428bd0a1a9900c9b2b6eba5becb795fda4037223))

## Changelog
