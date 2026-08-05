# Disclaimer

**lol.trq.alts** is an independent, community-maintained library. It is **not affiliated with,
endorsed by, sponsored by, or associated with** Mojang Studios, Microsoft, or any other party whose
services it interoperates with.

## Names and marks

"Minecraft", "Mojang", "Microsoft", "Xbox Live", and any other product or service names referenced in
this repository are the property of their respective owners. They appear here only to describe, factually
and accurately, what the library talks to — nominative use. No claim of ownership, partnership, or
approval is made or implied.

## What this library is

A protocol client and a local store. It speaks the publicly documented Microsoft OAuth 2.0, Xbox Live,
XSTS, and Minecraft services flows, and it keeps the resulting credentials in an encrypted file in a
directory the consuming application chooses. It does not bypass, defeat, or work around any access
control, licence check, or entitlement.

## Whose responsibility what is

The library does not obtain accounts, and it has no opinion about which ones you feed it.

- **How a consuming application acquires accounts**, and whether the person operating it is entitled to
  use them, is entirely that application's and that operator's responsibility.
- **Whether a given use complies** with the Minecraft End User Licence Agreement, the Microsoft Services
  Agreement, any server's rules, or the law where you are, is a question for you — not for this project.
- **Credentials belong to their owner.** Sharing one through the shared-vault feature hands durable
  access to whoever you share it with. Read the sharing-policy section of
  [docs/GETTING_STARTED.md](docs/GETTING_STARTED.md) before enabling it.

## No warranty

The library is provided **as is**, without warranty of any kind, express or implied, under the
[MIT licence](LICENSE). See the licence text for the full disclaimer of warranty and limitation of
liability.
