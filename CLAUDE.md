# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with this repository.

## What this is

**lol.trq.alts** is a standalone, renderer-agnostic Minecraft account-manager library for Fabric mods. It is consumed as a dependency (JitPack) or a git submodule; it must never depend on or reference any specific consumer mod.

## Hard rules

- **No consumer references.** This library is published. Never name, import, or describe any specific mod, client, launcher, product, sync server, or hosted instance that consumes it. Keep all examples generic ("your mod", "the host", `host`, `vault.example`). This applies to source, comments, Javadoc, docs, commit messages, and the README. The provenance fields (`AltAccount.sourceClient` / `sourceUser`) and federation addresses (`avp://host/repoId`) define field NAMES only — never bake a real client name, user, or server host into the library; those are runtime values the host supplies.
- **Host-agnostic boundary.** The library defines backend seam interfaces in `spi/`; hosts implement them. Never reach into a host type. No `import` of anything outside this repo's package root (`lol.trq.alts`), the JDK, or Gson.
- **Secrets stay out.** No API keys, tokens, or credentials in source or history. Any server API key lives host-side behind the `GameStatsSource` seam (or another host seam). Never commit `.idea/`, `*.iml`, or anything under the IDE/OS ignore globs.
- **House style.** palantir-java-format (`--palantir`, 4-space, 120-col). Records for DTOs with `@SerializedName` on every component. Full Javadoc on public + protected members. Run `./gradlew spotlessApply` before committing.
- **Conventional Commits.** `type(scope): summary`. Breaking changes use `!` + a `BREAKING CHANGE:` footer.

## Build

```bash
./gradlew build          # compile + test + spotlessCheck
./gradlew spotlessApply  # format
```

JDK 25 required.

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). The short version: `AltsRuntime` is the wiring root; `spi/` holds the host seam interfaces; `auth/` does the login flows (Microsoft config is host-supplied via `MicrosoftAuthConfig`); `store/` is the encrypted account file; `cache/` is the async lookup primitive; `model/` holds the records; `crypto/` + `vault/` are the zero-knowledge shared-repository layer (`SharedVault` over `vault/transport/` DTOs), and `vault/federation/` is the `avp://` addressing that makes repositories reachable across servers.
