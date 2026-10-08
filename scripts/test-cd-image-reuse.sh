#!/usr/bin/env bash
set -euo pipefail
source scripts/cd-image-reuse.sh
ssh_args=(-o StrictHostKeyChecking=yes -o UserKnownHostsFile=fixture-hosts)
BACKEND_DEPLOY_USER=fixture
BACKEND_DEPLOY_HOST=fixture.invalid
fixture_digest="sha256:$(printf 'a%.0s' {1..64})"
ssh_called=false
remote_present=true
docker() { printf '%s\n' "$fixture_digest"; }
ssh() {
  ssh_called=true
  [[ "$*" == *StrictHostKeyChecking=yes* ]] || return 1
  [[ "$*" == *UserKnownHostsFile=fixture-hosts* ]] || return 1
  [[ "$*" == *"docker image inspect $fixture_digest"* ]] || return 1
  [[ "$*" == *"docker image tag $fixture_digest trade-backend:fixture"* ]] || return 1
  "$remote_present"
}
reuse_remote_image trade-backend:fixture >/dev/null
[[ "$ssh_called" == true ]]
remote_present=false
if reuse_remote_image trade-backend:fixture >/dev/null; then
  echo 'missing remote digest must require transfer' >&2
  exit 1
fi
fixture_digest=invalid
ssh_called=false
if reuse_remote_image trade-backend:fixture >/dev/null; then
  echo 'invalid local digest must never reuse an image' >&2
  exit 1
fi
[[ "$ssh_called" == false ]]
echo 'CD exact image reuse: PASS'
