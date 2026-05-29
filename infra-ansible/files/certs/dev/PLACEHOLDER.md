# files/certs/dev/

Place the DEV environment SSL certificate files here:
- `server.crt`  — the certificate (public, safe to commit IF it is self-signed or internal CA)
- `server.key`  — the private key (**NEVER commit to Git** — use Ansible Vault or CI secret storage)

## Generating a self-signed cert for local testing

```bash
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout server.key \
  -out server.crt \
  -subj "/C=VN/ST=HCM/L=HoChiMinh/O=MyApp/CN=api-dev.myapp.internal"
```

## In production

Use a real certificate from Let's Encrypt or your company CA.
Store `server.key` in Jenkins Credentials (Secret File) — never on disk in this repo.
