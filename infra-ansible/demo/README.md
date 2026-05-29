# Ansible Demo — Local Testing with AWS EC2

Test the Ansible playbooks using an AWS EC2 instance as the target server.

```
Your PC (control node)
  │
  │  Ansible + SSH (ansible-aws.pem)
  ▼
EC2 Instance — Amazon Linux 2023 at 35.180.103.149  (managed node)
  - plays the role of "dev-app-01" from inventory/dev.yml
  - Ansible installs Java, HTTPD, systemd service here
```

---

## Prerequisites

- An AWS EC2 instance running (Amazon Linux 2023)
- `ansible-aws.pem` key file in the `demo/` folder
- Ansible installed on your PC:
  ```bash
  pip install ansible
  ```

---

## Step 1 — Fix key permissions

SSH rejects `.pem` files that are too open:

```bash
chmod 400 demo/ansible-aws.pem
```

---

## Step 2 — Create the deploy user on EC2

SSH in as the default AWS user first:

```bash
ssh -i demo/ansible-aws.pem ec2-user@35.180.103.149
```

Then on the server, create the `deploy` user that Ansible will use:

```bash
sudo useradd -m -s /bin/bash deploy
sudo mkdir -p /home/deploy/.ssh
sudo chmod 700 /home/deploy/.ssh
sudo cp ~/.ssh/authorized_keys /home/deploy/.ssh/authorized_keys
sudo chown -R deploy:deploy /home/deploy/.ssh
echo "deploy ALL=(ALL) NOPASSWD:ALL" | sudo tee /etc/sudoers.d/deploy
sudo chmod 440 /etc/sudoers.d/deploy
exit
```

---

## Step 3 — Verify SSH as deploy user

```bash
ssh -i demo/ansible-aws.pem deploy@35.180.103.149
```

If you get a shell prompt, Ansible can connect too.

---

## Step 4 — Run the Ansible playbook

Run from the `infra-ansible` folder:

```bash
# Real run — applies all changes to the EC2 instance
ansible-playbook playbooks/provision.yml \
  -i demo/inventory_aws.yml \
  --extra-vars "env_name=dev"

# Verbose output — shows each SSH command and task detail
ansible-playbook playbooks/provision.yml \
  -i demo/inventory_aws.yml \
  --extra-vars "env_name=dev" \
  -vvv
```

---

## What happens when the playbook runs

### 1. CLI parses the command

| Argument | Role |
|---|---|
| `playbooks/provision.yml` | The playbook to execute |
| `-i demo/inventory_aws.yml` | The inventory — defines who to connect to |
| `--extra-vars "env_name=dev"` | Injects `env_name=dev` so the playbook loads `group_vars/dev.yml` |

---

### 2. Ansible reads the inventory (`demo/inventory_aws.yml`)

```
host:    dev-app-01
IP:      35.180.103.149
user:    deploy
SSH key: demo/ansible-aws.pem
```

---

### 3. Ansible loads variables

The playbook loads `group_vars/dev.yml` via `vars_files`. This defines:

| Variable | Value |
|---|---|
| `java_package` | `java-21-amazon-corretto-headless` |
| `httpd_ssl_cert_src` | local path to `files/certs/dev/server.crt` |
| `httpd_ssl_key_src` | local path to `files/certs/dev/server.key` |
| `app_service_name` | `myapp` |
| `app_port` | `8080` |
| `app_domain` | `api-dev.myapp.internal` |

---

### 4. Ansible SSHs into the server — Gathering Facts

```
SSH → deploy@35.180.103.149 using ansible-aws.pem
```

Ansible collects information about the remote server:
- OS distribution → `Amazon` (used by `when: ansible_distribution == "Amazon"` conditions)
- Architecture, hostname, memory, Python path, etc.

---

### 5. Role: `java`

| Task | What happens on the server |
|---|---|
| Install Java | `dnf install java-21-amazon-corretto-headless` |
| Verify Java | runs `java -version` and prints the result |
| Set JAVA_HOME | writes `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto` to `/etc/environment` |

The Rocky Linux tasks are **skipped** because `ansible_distribution == "Amazon"`.

---

### 6. Role: `apache-httpd`

| Task | What happens on the server |
|---|---|
| Install HTTPD | `yum install httpd mod_ssl` |
| Create SSL dir | `mkdir -p /etc/httpd/ssl` |
| Copy certificate | uploads `files/certs/dev/server.crt` from your PC → `/etc/httpd/ssl/server.crt` on server |
| Copy private key | uploads `files/certs/dev/server.key` from your PC → `/etc/httpd/ssl/server.key` on server |
| Deploy vhost config | renders `vhost-ssl.conf.j2` template with real values → `/etc/httpd/conf.d/myapp.conf` |
| Enable HTTPD | `systemctl enable --now httpd` |

The vhost template configures:
- HTTP → HTTPS redirect on port 80
- HTTPS on port 443 using the uploaded SSL cert
- Reverse proxy: all HTTPS traffic forwarded to `http://127.0.0.1:8080` (Spring Boot)

---

### 7. Role: `app-service`

| Task | What happens on the server |
|---|---|
| Create OS user | `useradd myapp` with no login shell (security — app runs under dedicated user) |
| Create app dir | `mkdir /opt/myapp` owned by `myapp` |
| Deploy systemd unit | renders `app.service.j2` template → `/etc/systemd/system/myapp.service` |
| Enable service | `systemctl enable myapp` so it starts automatically on reboot |

The systemd unit defines how to start the Spring Boot JAR, which user runs it, and restart policy.

---

### 8. Handlers run (only if triggered)

Handlers only execute if a task that `notify`-ed them actually made a change:

| Handler | Triggered by | What it does |
|---|---|---|
| `Restart HTTPD` | vhost config changed | `systemctl restart httpd` |
| `Reload systemd` | service unit changed | `systemctl daemon-reload` |

---

### 9. Play Recap

```
dev-app-01 : ok=17  changed=13  unreachable=0  failed=0
```

- `ok` — tasks that ran (including already-correct ones)
- `changed` — tasks that actually modified something on the server
- If you run the playbook **a second time**, `changed` drops to 0 — Ansible is idempotent (safe to re-run)

---

## Files in this folder

```
demo/
├── Vagrantfile              # Defines the local VM (kept for reference)
├── ansible-aws.pem          # (gitignored) SSH key — downloaded from AWS
├── inventory_aws.yml        # Ansible inventory pointing to the EC2 instance
└── README.md                # This file
```

---

## How this maps to the real setup

| Demo (local) | Production |
|---|---|
| Your PC | Jenkins agent |
| EC2 instance | Real server (AWS EC2 / on-prem) |
| `ansible-aws.pem` | SSH key stored in Jenkins Credentials |
| `demo/inventory_aws.yml` | `inventory/dev.yml` with real server IPs |
