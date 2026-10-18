# Security

## Sensitive data

VPN subscription URLs, VLESS UUIDs, Hysteria2 authentication and Salamander obfuscation passwords can provide access to private infrastructure. Treat them as secrets.

Do not post the following in public issues or pull requests:

- full subscription URLs containing user IDs or tokens
- VLESS UUIDs
- Hysteria2 authentication and obfuscation passwords
- private server credentials
- private subscription-specific HWID values when tied to an account
- unredacted runtime logs containing credentials

When reporting a connection problem, redact credentials while preserving non-secret protocol, transport, REALITY/TLS settings and the error message needed to reproduce the issue.

## Subscription device identity

On Android and Windows, `x-hwid` and basic client/OS metadata are sent to the subscription origin over its HTTP(S) fetch/refresh request, not embedded in VPN traffic. Cross-origin redirects drop these identity headers. HWIDs are random pseudonyms (or user-chosen) rather than physical fingerprints; they should not be treated as strong proof of one device per subscription. See [subscription identity](docs/SUBSCRIPTION_IDENTITY.md).

## Reporting a security issue

For issues that would expose another user's credentials or private infrastructure, avoid publishing exploit details or secrets in a public issue. Contact the repository owner privately first.
