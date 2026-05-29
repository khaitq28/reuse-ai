# infra-ansible

Ansible project for provisioning application servers used by the MyApp platform.

**Objective:** understand how Ansible works and how to apply it in a real CI/CD project
alongside existing Jenkins pipelines.

---

## Table of Contents

1. [What is Ansible and how does it work?](#1-what-is-ansible-and-how-does-it-work)
2. [How Ansible SSHes into servers — credentials explained](#2-how-ansible-sshes-into-servers--credentials-explained)
3. [Project structure](#3-project-structure)
4. [Environments and inventory](#4-environments-and-inventory)
5. [What each role does](#5-what-each-role-does)
6. [How to add a new server](#6-how-to-add-a-new-server)
7. [Running via Jenkins](#7-running-via-jenkins)
8. [Running locally (for development/testing)](#8-running-locally-for-developmenttesting)
9. [SSL certificates — where they live and how they get there](#9-ssl-certificates--where-they-live-and-how-they-get-there)
10. [How this fits with the existing Jenkins CI/CD pipeline](#10-how-this-fits-with-the-existing-jenkins-cicd-pipeline)
11. [Variable precedence — where values come from](#11-variable-precedence--where-values-come-from)

---

## 1. What is Ansible and how does it work?

Ansible is a tool that lets you describe the **desired state** of a server in YAML files
called **playbooks**, then reaches out to the server and makes it match that state.

### Key concepts

| Term | Meaning |
|---|---|
| **Control node** | The machine that *runs* Ansible (Jenkins agent, your laptop) |
| **Managed node** | The remote server Ansible configures (dev-app-01, uat-app-01…) |
| **Inventory** | A file listing the managed nodes and their connection details |
| **Playbook** | A YAML file that describes *what* to do (install Java, copy a file…) |
| **Role** | A reusable group of tasks (java role, apache-httpd role…) |
| **Task** | A single action (install a package, restart a service, copy a file) |
| **Handler** | Like a task but only runs when *notified* — used for service restarts |
| **Template** | A file with `{{ variable }}` placeholders that Ansible fills in per host |
| **Module** | A built-in Ansible function — `yum`, `copy`, `template`, `systemd`, etc. |

### How a playbook run works (step by step)

```
You run:
  ansible-playbook playbooks/provision.yml -i inventory/dev.yml

Ansible:
  1. Reads inventory/dev.yml → finds host dev-app-01 at 192.168.10.10
  2. SSHes into 192.168.10.10 as user "deploy"
  3. Collects facts (OS version, architecture, disk space…) — gather_facts: true
  4. Executes each task in order:
       - Install java-21-openjdk via yum
       - Create /etc/httpd/ssl/ directory
       - Copy server.crt to /etc/httpd/ssl/server.crt
       - Copy server.key to /etc/httpd/ssl/server.key
       - Render vhost-ssl.conf.j2 template → /etc/httpd/conf.d/myapp-ssl.conf
       - Start and enable httpd service
       - Create OS user "myapp"
       - Create /opt/myapp directory
       - Deploy systemd unit file
       - Enable myapp service
  5. Runs any handlers that were notified (e.g. restart httpd if cert changed)
  6. Reports a summary: ok=10 changed=7 failed=0
```

### Idempotency — the most important concept

Every task checks *current state* before acting.
If Java 21 is already installed → the task reports `ok` (skipped), not `changed`.
If the cert file already exists and is identical → no copy, no restart.

This means you can **run the same playbook multiple times safely** — it only makes
changes when something is actually different. This is what makes Ansible reliable for
automation.

---

## 2. How Ansible SSHes into servers — credentials explained

Ansible uses **standard SSH** under the hood. No agent needs to be installed on the
remote server. You only need:

1. An SSH user that exists on the server (`deploy` in this project)
2. An SSH private key that matches a public key in that user's `~/.ssh/authorized_keys`

### The flow

```
Control node (Jenkins / your laptop)
  │
  │  Has: SSH private key  ←── stored securely (Jenkins Credentials / ssh-agent)
  │
  └──SSH──► Remote server (192.168.10.10)
              Has: ~/.ssh/authorized_keys containing the matching PUBLIC key
              Ansible logs in as user "deploy"
              Escalates to root via sudo (become: true) for privileged tasks
```

### Where the private key is stored

| Context | Where the key lives | How Ansible gets it |
|---|---|---|
| **Jenkins** | Jenkins Credentials store → type: "SSH Username with private key", ID: `ansible-ssh-key` | `sshagent(credentials: ['ansible-ssh-key'])` in Jenkinsfile — Jenkins injects it into SSH agent |
| **Local dev** | Your `~/.ssh/id_rsa` or a named key | `ssh-add ~/.ssh/my-key` before running, or set `ansible_ssh_private_key_file` |
| **Inventory file** | Variable `ansible_ssh_private_key_file` | Points to the key file path on the control node |

### Setting up the server to accept Ansible

Before Ansible can connect, someone (a sysadmin) must manually:

```bash
# On the remote server — done ONCE when the server is first created
adduser deploy
mkdir -p /home/deploy/.ssh
echo "ssh-rsa AAAA...your-public-key... jenkins@ci" >> /home/deploy/.ssh/authorized_keys
chmod 600 /home/deploy/.ssh/authorized_keys
chmod 700 /home/deploy/.ssh

# Allow deploy user to sudo without password (for Ansible become: true tasks)
echo "deploy ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers.d/deploy
```

After this one-time setup, Ansible can fully manage the server.

### Adding the key to Jenkins

```
Jenkins → Manage Jenkins → Credentials → System → Global credentials
  → Add Credentials
    Kind:       SSH Username with private key
    ID:         ansible-ssh-key          ← must match Jenkinsfile
    Username:   deploy
    Private Key: [paste the private key content]
```

---

## 3. Project structure

```
infra-ansible/
│
├── inventory/                  # WHO to configure
│   ├── dev.yml                 # DEV servers + connection details
│   ├── uat.yml                 # UAT servers
│   └── prod.yml                # PROD servers
│
├── group_vars/                 # WHAT values to use per environment
│   ├── dev.yml                 # Variables for all hosts in the "dev" group
│   ├── uat.yml
│   └── prod.yml
│
├── playbooks/                  # WHAT to do
│   └── provision.yml           # Main playbook — calls all three roles
│
├── roles/                      # Reusable units of work
│   ├── java/                   # Install Java 21
│   │   ├── tasks/main.yml
│   │   └── defaults/main.yml
│   │
│   ├── apache-httpd/           # Install HTTPD, copy cert, configure HTTPS
│   │   ├── tasks/main.yml
│   │   ├── handlers/main.yml   # Restart HTTPD when cert/config changes
│   │   ├── templates/
│   │   │   └── vhost-ssl.conf.j2   # HTTPS virtual host config (Jinja2 template)
│   │   └── defaults/main.yml
│   │
│   └── app-service/            # Create OS user, app directory, systemd service
│       ├── tasks/main.yml
│       ├── handlers/main.yml
│       ├── templates/
│       │   └── app.service.j2  # systemd unit file for Spring Boot app
│       └── defaults/main.yml
│
├── files/
│   └── certs/
│       ├── dev/                # SSL cert for DEV (server.crt + server.key)
│       ├── uat/                # SSL cert for UAT
│       └── prod/               # SSL cert for PROD
│
├── Jenkinsfile                 # Jenkins pipeline to trigger provisioning
├── .gitignore                  # Excludes *.key files from Git
└── README.md
```

---

## 4. Environments and inventory

Each environment has its own inventory file listing its servers.

### Example: `inventory/dev.yml`

```yaml
all:
  children:
    dev:
      hosts:
        dev-app-01:
          ansible_host: 192.168.10.10    # IP Ansible SSHes into
          ansible_user: deploy            # OS user
          ansible_port: 22
          ansible_ssh_private_key_file: "{{ ssh_key_path }}"
          app_domain: api-dev.myapp.internal
          app_port: 8080
```

### Simulated server list

| Environment | Hostname | IP | Domain |
|---|---|---|---|
| DEV | dev-app-01 | 192.168.10.10 | api-dev.myapp.internal |
| UAT | uat-app-01 | 192.168.20.10 | api-uat.myapp.com |
| PROD | prod-app-01 | 192.168.30.10 | api.myapp.com |
| PROD | prod-app-02 | 192.168.30.11 | api.myapp.com |

---

## 5. What each role does

### Role: `java`

Installs OpenJDK 21 on the server.

```
Tasks:
  1. yum install java-21-openjdk
  2. Verify: java -version
  3. Set JAVA_HOME in /etc/environment
```

### Role: `apache-httpd`

Installs Apache HTTPD with `mod_ssl`, deploys the SSL certificate, and configures
a virtual host that:
- Redirects HTTP (port 80) → HTTPS (port 443)
- Terminates TLS using the provided certificate
- Proxies all HTTPS requests to the Spring Boot app at `localhost:8080`

```
Tasks:
  1. yum install httpd mod_ssl
  2. mkdir /etc/httpd/ssl  (mode 700 — only root)
  3. copy server.crt → /etc/httpd/ssl/server.crt
  4. copy server.key → /etc/httpd/ssl/server.key  (mode 600 — private!)
  5. template vhost-ssl.conf.j2 → /etc/httpd/conf.d/myapp-ssl.conf
  6. systemctl enable --now httpd

Handler (runs if cert or config changed):
  - systemctl restart httpd
```

The template `vhost-ssl.conf.j2` is rendered per-host, substituting
`{{ app_domain }}`, `{{ app_port }}`, `{{ httpd_ssl_cert_dest }}`, etc.

### Role: `app-service`

Prepares the server to run the Spring Boot application.

```
Tasks:
  1. Create OS user "myapp" (no login shell — security)
  2. Create /opt/myapp directory (owned by myapp user)
  3. Template app.service.j2 → /etc/systemd/system/myapp.service
  4. systemctl enable myapp (does NOT start — Jenkins deploys the JAR later)
```

The systemd unit file sets:
- Which user runs the process (`myapp`)
- The JAR path (`/opt/myapp/app.jar`)
- Spring profile (`SPRING_PROFILES_ACTIVE=dev/uat/prod`)
- JVM memory flags
- Auto-restart on failure

---

## 6. How to add a new server

Say you need a second UAT server `uat-app-02` at `192.168.20.11`.

**Step 1** — Add it to the inventory:

```yaml
# inventory/uat.yml
all:
  children:
    uat:
      hosts:
        uat-app-01:
          ansible_host: 192.168.20.10
          ...
        uat-app-02:                       # ← add this
          ansible_host: 192.168.20.11
          ansible_user: deploy
          ansible_port: 22
          ansible_ssh_private_key_file: "{{ ssh_key_path }}"
          app_domain: api-uat.myapp.com
          app_port: 8080
```

**Step 2** — Commit and push to GitLab.

**Step 3** — Go to Jenkins → `infra-provision` job → Build with Parameters:
```
ENV         = uat
LIMIT_HOST  = uat-app-02    ← only runs on the new server, not uat-app-01
```

That's it. Ansible SSHes into `uat-app-02`, installs Java 21, configures HTTPD with
the UAT certificate, and sets up the systemd service. `uat-app-01` is untouched.

---

## 7. Running via Jenkins

### Jenkins job setup

1. Create a new Pipeline job in Jenkins named `infra-provision`
2. Point it to this GitLab repo, `Jenkinsfile` as the pipeline script
3. Make sure the Jenkins agent has Ansible installed:
   ```bash
   pip install ansible        # or: yum install ansible
   ```
4. Add the SSH credential:
   ```
   Jenkins → Credentials → Add
   Kind: SSH Username with private key
   ID:   ansible-ssh-key
   ```

### Triggering a run

```
Jenkins → infra-provision → Build with Parameters

  ENV         = dev            (or uat / prod)
  LIMIT_HOST  =                (empty = all hosts in inventory)
               dev-app-02      (specific = only that host)
```

The Jenkinsfile uses `sshagent` to inject the private key so Ansible can SSH in
without the key ever being written to disk.

---

## 8. Running locally (for development/testing)

```bash
# Install Ansible on your machine
pip install ansible

# Add your SSH key to the agent
ssh-add ~/.ssh/your-deploy-key

# Dry run (--check = show what WOULD change, don't actually do it)
ansible-playbook playbooks/provision.yml \
  -i inventory/dev.yml \
  --check \
  --extra-vars "env_name=dev"

# Real run against a single host
ansible-playbook playbooks/provision.yml \
  -i inventory/dev.yml \
  --limit dev-app-01 \
  --extra-vars "env_name=dev"

# Verbose output (shows each SSH command)
ansible-playbook playbooks/provision.yml -i inventory/dev.yml -vvv
```

---

## 9. SSL certificates — where they live and how they get there

```
infra-ansible/
└── files/
    └── certs/
        ├── dev/
        │   ├── server.crt   ← public cert (can commit if internal CA)
        │   └── server.key   ← NEVER commit (in .gitignore)
        ├── uat/
        └── prod/
```

The `apache-httpd` role uses the `copy` module to push these files:

```yaml
- name: Copy SSL certificate to server
  ansible.builtin.copy:
    src: "{{ httpd_ssl_cert_src }}"      # local: files/certs/dev/server.crt
    dest: "{{ httpd_ssl_cert_dest }}"    # remote: /etc/httpd/ssl/server.crt
```

`src` is a path relative to the Ansible project root on the **control node**.
`dest` is the absolute path on the **remote server**.

### Private key security

The `server.key` files are in `.gitignore` — they must **never** be committed.

For real environments, store keys in:
- **Jenkins Credentials** (Secret File type) → Jenkins writes it to a temp file, passes path to Ansible
- **Ansible Vault** → encrypts the key inside the repo itself (`ansible-vault encrypt server.key`)

### Generating a self-signed cert for testing

```bash
cd files/certs/dev
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout server.key \
  -out server.crt \
  -subj "/C=VN/ST=HCM/L=HoChiMinh/O=MyApp/CN=api-dev.myapp.internal"
```

---

## 10. How this fits with the existing Jenkins CI/CD pipeline

```
Developer pushes code to GitLab
        │
        ▼
Jenkins (api-a or api-b pipeline — Shared Jenkinsfile)
  1. Build JAR
  2. Run tests
  3. Upload JAR to Nexus
  4. SSH into server → copy JAR to /opt/myapp/app.jar
  5. systemctl restart myapp
        │
        ▼
Spring Boot app running on port 8080
        │
        ▼
Apache HTTPD (port 443)  ← configured by Ansible (one-time)
  - Terminates TLS
  - Proxies to localhost:8080
        │
        ▼
Client (browser / other API)
```

**Ansible** handles the infrastructure layer (one-time setup per server).
**Jenkins + Shared Jenkinsfile** handles the application layer (every release).

They are **independent**. You never need to re-run Ansible when deploying a new JAR
version. You only re-run Ansible when a server needs reconfiguration (new cert,
Java upgrade, new server added).

---

## 11. Variable precedence — where values come from

Ansible merges variables from multiple sources. From lowest to highest priority:

```
roles/*/defaults/main.yml       ← fallback defaults (lowest priority)
        ↓
group_vars/dev.yml              ← environment-specific values
        ↓
inventory/dev.yml (host vars)   ← per-host values (app_domain, app_port)
        ↓
--extra-vars on command line    ← Jenkins passes env_name here (highest priority)
```

Example — `app_port` resolution when running on `dev-app-01`:

```
roles/apache-httpd/defaults/main.yml  →  app_port: 8080
group_vars/dev.yml                    →  (not set — inherits default)
inventory/dev.yml (dev-app-01)        →  app_port: 8080   ← host-level override wins
```

If you needed dev-app-01 to use port 9090, you would set it in `inventory/dev.yml`
under that host — no need to touch the role at all.
