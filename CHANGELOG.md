# Changelog

All notable changes to **SpiralGenesis** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added
- Initial project scaffolding with Gradle Kotlin DSL and Java 21 toolchain.
- Deterministic 2D square spiral coordinate transformation engine (`SpiralMath`).
- Asynchronous Paper chunk and heightmap probing with ocean and hazard filtering (`SpawnManager`).
- Cross-play authentication parity for Bedrock (`Floodgate`) and Java (`AuthMe-Reloaded`).
- Persistent per-player YAML storage (`data.yml`).
- Administrative `/sgen` command suite (`setcenter`, `setspawn`, `reassign`, `tp`, `info`, `reload`).
- Automated multi-tier release workflows for GitHub Releases, Modrinth, and Paper Hangar.
- JUnit 5 test suite for mathematical verification.
