#!/usr/bin/env bash
set -u
export PAGER=cat
export SYSTEMD_PAGER=cat
export GIT_PAGER=cat

section() {
  printf '\n\n==================== %s ====================\n' "$1"
}

section "DATE / HOST / UPTIME"
date -Is
hostnamectl 2>/dev/null || hostname
uptime

section "OS / KERNEL"
cat /etc/os-release 2>/dev/null || true
uname -a

section "CPU / MEMORY / DISKS"
lscpu 2>/dev/null || true
free -h
lsblk -o NAME,SIZE,FSTYPE,TYPE,MOUNTPOINTS
printf '\nDisk usage:\n'
df -hT
printf '\nInodes:\n'
df -hi

section "NETWORK ADDRESSES / ROUTES"
ip -br addr 2>/dev/null || ip addr
ip route

section "LISTENING PORTS"
ss -lntup 2>/dev/null || netstat -lntup 2>/dev/null || true

section "DOCKER VERSION / INFO"
docker version 2>&1 || true
docker info 2>&1 || true

action_docker() {
  section "DOCKER CONTAINERS"
  docker ps -a --no-trunc 2>&1 || true
  section "DOCKER IMAGES"
  docker images --no-trunc 2>&1 || true
  section "DOCKER NETWORKS"
  docker network ls 2>&1 || true
  section "DOCKER VOLUMES"
  docker volume ls 2>&1 || true
  section "DOCKER DISK USAGE"
  docker system df -v 2>&1 || true
  section "COMPOSE PROJECTS"
  docker compose ls -a 2>&1 || true
}
action_docker

section "RUNNING SERVICES"
systemctl list-units --type=service --state=running --no-pager --plain 2>&1 || true

section "FAILED SERVICES"
systemctl --failed --no-pager --plain 2>&1 || true

section "WEB SERVERS / PROXIES"
for cmd in nginx apache2 httpd caddy traefik; do
  command -v "$cmd" >/dev/null 2>&1 && "$cmd" -v 2>&1 || true
done
ps auxww | grep -E '[n]ginx|[a]pache2|[h]ttpd|[c]addy|[t]raefik' || true

section "NGINX CONFIG TEST / SAFE ROUTING SUMMARY"
if command -v nginx >/dev/null 2>&1; then
  nginx -t 2>&1 || true
  echo "Enabled config files:"
  find /etc/nginx/sites-enabled /etc/nginx/conf.d -maxdepth 1 -type f -o -type l 2>/dev/null | sort || true
  echo "Listen/server_name/proxy_pass directives (secret-bearing headers omitted):"
  grep -RHE '^[[:space:]]*(listen|server_name|proxy_pass)[[:space:]]' \
    /etc/nginx/sites-enabled /etc/nginx/conf.d 2>/dev/null || true
fi

section "FIREWALL"
ufw status verbose 2>&1 || true
firewall-cmd --state 2>&1 || true
firewall-cmd --list-all 2>&1 || true
iptables-save 2>&1 || true
nft list ruleset 2>&1 || true

section "JAVA / MAVEN / GIT"
java -version 2>&1 || true
mvn -version 2>&1 || true
git --version 2>&1 || true

section "TOP PROCESSES"
ps auxww --sort=-%mem | head -n 40
printf '\nTop by CPU:\n'
ps auxww --sort=-%cpu | head -n 40

section "COMMON APPLICATION DIRECTORIES"
for d in /opt /srv /var/www /home /root; do
  echo "--- $d"
  find "$d" -maxdepth 2 -mindepth 1 -type d -printf '%p\n' 2>/dev/null | head -n 300
 done

section "TIMERS / CRON FILE NAMES"
systemctl list-timers --all --no-pager 2>&1 || true
find /etc/cron.d /etc/cron.daily /etc/cron.hourly /etc/cron.weekly /etc/cron.monthly \
  -maxdepth 1 -type f -printf '%p\n' 2>/dev/null | sort || true

section "RECENT SYSTEM ERRORS"
journalctl -p warning..alert -n 300 --no-pager 2>&1 || true

section "DONE"
echo "Diagnostics complete"
