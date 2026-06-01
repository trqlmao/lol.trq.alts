# Security Policy

## Supported versions

The latest released version on the `main` branch receives security updates.

## Reporting a vulnerability

Please report security vulnerabilities privately via [GitHub Security Advisories](https://github.com/trqlmao/lol.trq.alts/security/advisories/new) rather than opening a public issue.

We aim to acknowledge reports within 72 hours and to ship a fix or mitigation as quickly as the severity warrants.

Do not disclose the issue publicly until a fix has been released.

## Scope notes

This library handles Minecraft account credentials. The on-disk store is encrypted (AES-256-GCM + PBKDF2) and bound to the host machine; credentials are never transmitted by the library itself. API keys for optional integrations are supplied by the host through a seam and are never held or persisted by the library.
