# AGENTS.md

See [CLAUDE.md](CLAUDE.md) for the full guidance — mental model, patterns, and pitfalls. The short
version for any agent working here:

- This is a **published, consumer-agnostic library**. Never reference a specific consuming mod, client,
  product, sync server, or hosted instance — in source, comments, docs, tests, or commit messages.
  Provenance fields and `avp://` addresses define field names only; the real values are host-supplied at
  runtime. CI fails the build on a leak.
- **The boundary is `spi/`.** The library never imports a host type. If a change seems to need one, it
  needs a new seam instead. Only the JDK, Gson, and `lol.trq.alts` may be imported.
- **No secrets, no IDE files, no machine paths.** Never commit API keys, tokens, `.idea/`, or `*.iml`.
  Host-side seams supply every credential.
- **Failures are typed.** Branch on `AltLoginCallback.FailureReason`, never on a message string, and keep
  a refused credential (`INVALID_TOKEN` / `REAUTH_REQUIRED`) classified apart from an unreachable service
  (`NETWORK`).
- **Credentials are handled carefully.** A refresh token is durable: redact it from `toString`, discard it
  only on a stated `invalid_grant`, and never write it for an account the store does not already hold. An
  unreadable store file is never overwritten.
- **Style:** palantir-java-format (4-space, 120-col), records for DTOs with `@SerializedName` on every
  component, full Javadoc with `@since`. Run `./gradlew spotlessApply`, then `./gradlew check`.
- **Docs snippets are compiled.** Change the method in `examples/` first, then the guide that inlines it.
- **Commits:** Conventional Commits, one logical change each, and never a `Co-Authored-By` or any other
  AI-attribution trailer. You own what you ship.
