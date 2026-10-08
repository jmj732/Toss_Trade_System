#!/usr/bin/env bash
set -euo pipefail
source scripts/cd-image-reuse.sh
ssh_args=(-o StrictHostKeyChecking=yes -o UserKnownHostsFile=fixture-hosts)
BACKEND_DEPLOY_USER=fixture
BACKEND_DEPLOY_HOST=fixture.invalid
fixture_dir="$(mktemp -d)"
trap 'rm -rf -- "$fixture_dir"' EXIT
export fixture_dir
export fixture_remote_config='{"Cmd":["java"],"Env":["A=B"]}'
export fixture_remote_layers='{"Type":"layers","Layers":["sha256:layer"]}'
export fixture_remote_arch=amd64
export fixture_remote_missing=false

docker() {
  case "$1 $2" in
    'image ls')
      "$fixture_remote_missing" || printf '%s\n' 'trade-backend:previous'
      ;;
    'image inspect')
      [[ "${DOCKER_API_VERSION:-}" == 1.44 ]] || return 1
      if [[ "${REMOTE_FIXTURE:-false}" == true ]]; then
        printf '%s|%s|linux|%s|\n' "$fixture_remote_config" "$fixture_remote_layers" "$fixture_remote_arch"
      else
        printf '%s\n' '{"Cmd":["java"],"Env":["A=B"]}|{"Type":"layers","Layers":["sha256:layer"]}|linux|amd64|'
      fi
      ;;
    'image tag') printf '%s\n' "$3 $4" >"$fixture_dir/tagged" ;;
    *) return 1 ;;
  esac
}
export -f docker
ssh() {
  [[ "$*" == *StrictHostKeyChecking=yes* ]] || return 1
  [[ "$*" == *UserKnownHostsFile=fixture-hosts* ]] || return 1
  REMOTE_FIXTURE=true bash -c "${*: -1}"
}
reuse_remote_image trade-backend:current >/dev/null
[[ "$(cat "$fixture_dir/tagged")" == 'trade-backend:previous trade-backend:current' ]]
rm "$fixture_dir/tagged"
fixture_remote_config='{"Cmd":["different"],"Env":["A=B"]}'
if reuse_remote_image trade-backend:current >/dev/null; then exit 1; fi
fixture_remote_config='{"Cmd":["java"],"Env":["A=B"]}'
fixture_remote_layers='{"Type":"layers","Layers":["sha256:other"]}'
if reuse_remote_image trade-backend:current >/dev/null; then exit 1; fi
fixture_remote_layers='{"Type":"layers","Layers":["sha256:layer"]}'
fixture_remote_arch=arm64
if reuse_remote_image trade-backend:current >/dev/null; then exit 1; fi
fixture_remote_arch=amd64
fixture_remote_missing=true
if reuse_remote_image trade-backend:current >/dev/null; then exit 1; fi
[[ ! -f "$fixture_dir/tagged" ]]
echo 'CD verified image content reuse: PASS'
