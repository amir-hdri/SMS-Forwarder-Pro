# Changelog

## 2.1.3 candidate — 2026-11-03

- Start Driver activation automatically after Android permissions, with no typed phone, enrollment link or technical fields; derive registration from authenticated Hub carrier-origin proof and a v2 device-bound encrypted grant.
- Persist activation SMS attempts and use the existing modem callback/PDU machinery for bounded same-nonce retries; local radio/delivery acknowledgements do not mark enrollment active.
- Preserve an existing Hub private profile and enrolled Driver settings while seeding public role-specific config/update channels. Reject public Driver endpoint/token overrides and recover a failed download of the same authenticated release.
- Keep the existing HTTPS CA and Android APK signer. Establish the first persistent ECDSA public-channel trust key. This is a source/build candidate, not a handset installation or a published release.

Evidence and runtime limits: [AUTOMATIC_ACTIVATION.md](AUTOMATIC_ACTIVATION.md).
