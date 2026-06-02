## Summary

<!-- What does this change, and why? -->

## Checklist

- [ ] No vendor-internal names. The `no-leak` CI gate must pass; lol.trq.alts is vendor-neutral (no client, product, sponsor, or host names).
- [ ] `./gradlew spotlessCheck build` passes locally (JDK 25 toolchain).
- [ ] Tests added/updated for the change.
- [ ] `CHANGELOG.md` updated; a public API change follows SemVer and is marked Breaking where applicable.
