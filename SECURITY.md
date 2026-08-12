# Security

## Sensitive data

VPN subscription URLs and VLESS credentials can provide access to private infrastructure. Treat them as secrets.

Do not post the following in public issues or pull requests:

- full subscription URLs containing user IDs or tokens
- VLESS UUIDs
- private server credentials
- unredacted runtime logs containing credentials

When reporting a connection problem, redact credentials while preserving protocol, transport, REALITY/TLS parameters and the error message needed to reproduce the issue.

## Reporting a security issue

For issues that would expose another user's credentials or private infrastructure, avoid publishing exploit details or secrets in a public issue. Contact the repository owner privately first.
