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

Keep the subject under ~72 characters, lowercase after the colon, imperative mood, no trailing period. One logical change per commit — if two types fit, it is two commits.

## Commit ownership

**You are the responsible author of every commit you ship, including code an AI wrote.** AI assistance is a tool, not a co-author: commits must never carry a `Co-Authored-By: <assistant>` or any other AI-attribution trailer, and a commit that has one should be rewritten before it is pushed.

Owning the commit means reviewing what was produced as if you had written it — its correctness, its security, and what it might leak. This matters more than usual here, because the library holds credentials and is published.

## Documentation

Every Java snippet in [docs/GETTING_STARTED.md](docs/GETTING_STARTED.md) is inlined from a method in [`examples/`](examples/), which the `examples` source set compiles on every build. Change the example first, then the guide — that way an API change breaks the build instead of quietly rotting the docs.

New public and protected members need Javadoc with an `@since` carrying the version they will first ship in, and a `CHANGELOG.md` entry under `[Unreleased]`.

## Pull requests

- Keep PRs focused on a single change.
- Ensure `./gradlew build` passes (compile + test + spotlessCheck).
- Describe the motivation and the approach in the PR body.

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
