# Contributing to lol.trq.alts

Thanks for your interest in contributing!

## Getting started

1. Fork the repository and clone your fork.
2. Ensure you have **JDK 25** installed.
3. Build: `./gradlew build`.

## Development workflow

- **Format before committing:** `./gradlew spotlessApply`. CI rejects unformatted code.
- **House style:** palantir-java-format (4-space indent, 120-column limit). Records for DTOs with `@SerializedName` on every component. Full Javadoc on public and protected members.
- **Tests:** add JUnit 5 tests for new behaviour; run `./gradlew test`.
- **Keep it host-agnostic:** the library must never import or reference a specific consuming mod, and must never hold secrets — credentials and API keys come from host-implemented seams.

## Commit messages

This project uses [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <summary>
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`. Breaking changes append `!` and a `BREAKING CHANGE:` footer.

## Pull requests

- Keep PRs focused on a single change.
- Ensure `./gradlew build` passes (compile + test + spotlessCheck).
- Describe the motivation and the approach in the PR body.

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
