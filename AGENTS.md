# AGENTS.md

See [CLAUDE.md](CLAUDE.md) for full guidance. Key points for any agent working here:

- This is a **published, consumer-agnostic library**. Never reference a specific consuming mod, client, product, sync server, or hosted instance — in source, comments, docs, or commit messages. Provenance fields and `avp://` addresses define field names only; real client/user/server values are host-supplied at runtime.
- **No secrets, no IDE files.** Never commit API keys, tokens, `.idea/`, or `*.iml`. Host-side seams supply all credentials.
- House style: palantir-java-format (4-space, 120-col), records for DTOs with `@SerializedName` on every component, full Javadoc. Run `./gradlew spotlessApply`.
- Conventional Commits with `!`/`BREAKING CHANGE:` for breaks.
- Keep the backend-seam boundary clean: the library never imports a host type.
