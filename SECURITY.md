# Security Policy

## Supported versions

The latest released version on the `main` branch receives security updates.

## Reporting a vulnerability

Please report security vulnerabilities privately via [GitHub Security Advisories](https://github.com/trqlmao/lol.trq.alts/security/advisories/new) rather than opening a public issue.

We aim to acknowledge reports within 72 hours and to ship a fix or mitigation as quickly as the severity warrants.

Do not disclose the issue publicly until a fix has been released.

## Scope notes

This library handles Minecraft account credentials. The on-disk store is encrypted (AES-256-GCM + PBKDF2) and bound to the host machine; credentials are never transmitted by the library itself. API keys for optional integrations are supplied by the host through a seam and are never held or persisted by the library.

## Threat model

Worth being explicit about what the two encryption layers do and do not buy, so nobody over-trusts them.

**The local store (`AltStore`, `SecretStore`).** The PBKDF2 password is derived from machine properties, not from a user secret. That stops the file being copied to another machine and read there, and it keeps credentials off disk in plaintext. It does **not** protect against an attacker who can already run code as the same user on the same machine — they can derive the same key. Treat the store as protection against file exfiltration, not against local compromise. A user who wants more should use full-disk or per-user encryption underneath it.

**The shared vault (`SharedVault`).** This one is a real zero-knowledge design: the server sees only ciphertext, wrapped keys, public keys, and counters. Two boundaries are documented rather than solved:

- The refresh-token sharing policy binds **members**, not the **manifest host**. `shareRefreshTokens` is plain manifest metadata with no signature over it and no binding into the payload AAD, so a malicious or compromised host can serve `true` for a repository created withholding. Authenticating the manifest is open work.
- Opting a repository into refresh-token sharing is effectively irreversible. `removeMember` and `rotateKey` re-key future payloads, but a refresh token already on a member's disk can only be revoked at the identity provider.

Both are described in more detail in [docs/GETTING_STARTED.md](docs/GETTING_STARTED.md) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Reports that either boundary is narrower than described are in scope; reports that they exist are not, since they are stated here.

**Out of scope.** How a consuming application acquires the accounts it feeds this library, and whether that use complies with any service's terms, is that application's responsibility — see [DISCLAIMER.md](DISCLAIMER.md).
