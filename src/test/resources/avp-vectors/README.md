# AVP conformance vectors (vendored)

These JSON files are copied verbatim from the Alt Vault Protocol spec repository,
`trqlmao/avp` under `vectors/` (as of commit `5fb48e8`). They are the **source of
truth**; do not edit them here. To update, re-copy from `trqlmao/avp/vectors/` and
adjust `AvpConformanceVectorsTest` if a vector's shape changes.

`AvpConformanceVectorsTest` loads each file and asserts this library's crypto
reproduces it byte for byte, so the implementation cannot silently drift from the
published wire contract.
