#!/bin/bash
# reset-ec2.sh
# Removes everything Ansible installed — returns EC2 to clean Amazon Linux 2023 state.
# Run from the infra-ansible folder:
#   bash demo/reset-ec2.sh

set -e

SSH_KEY="ansible-aws.pem"
HOST="deploy@35.180.103.149"

echo "==> Connecting to EC2 and resetting server..."

ssh -i "$SSH_KEY" "$HOST" 'bash -s' <<'REMOTE'
set -e

echo "--- Stopping and removing myapp service ---"
sudo systemctl stop myapp 2>/dev/null || true
sudo systemctl disable myapp 2>/dev/null || true
sudo rm -f /etc/systemd/system/myapp.service
sudo systemctl daemon-reload

echo "--- Removing myapp user and directory ---"
sudo userdel -r myapp 2>/dev/null || true
sudo rm -rf /opt/myapp

echo "--- Stopping and removing Apache HTTPD ---"
sudo systemctl stop httpd 2>/dev/null || true
sudo systemctl disable httpd 2>/dev/null || true
sudo dnf remove -y httpd mod_ssl 2>/dev/null || true
sudo rm -rf /etc/httpd
sudo rm -rf /var/log/httpd

echo "--- Removing Java ---"
sudo dnf remove -y java-21-amazon-corretto-headless 2>/dev/null || true
sudo sed -i '/^JAVA_HOME=/d' /etc/environment

echo "--- Verifying clean state ---"
java -version 2>/dev/null && echo "WARNING: Java still installed" || echo "OK: Java removed"
systemctl status httpd 2>/dev/null && echo "WARNING: HTTPD still present" || echo "OK: HTTPD removed"
systemctl status myapp 2>/dev/null && echo "WARNING: myapp service still present" || echo "OK: myapp service removed"
id myapp 2>/dev/null && echo "WARNING: myapp user still exists" || echo "OK: myapp user removed"

echo "--- Done. Server is reset to clean state. ---"
REMOTE
