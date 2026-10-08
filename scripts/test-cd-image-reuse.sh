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
      local config layers arch
      if [[ "${REMOTE_FIXTURE:-false}" == true ]]; then
        config="$fixture_remote_config"; layers="$fixture_remote_layers"; arch="$fixture_remote_arch"
      else
        config='{"Cmd":["java"],"Env":["A=B"]}'
        layers='{"Type":"layers","Layers":["sha256:layer"]}'
        arch=amd64
      fi
      if [[ "$4" == *'range '* ]]; then
        config="$(FIXTURE_CONFIG="$config" python3 -c 'import json,os; d=json.loads(os.environ["FIXTURE_CONFIG"]); print("".join(json.dumps(k)+"="+json.dumps(v,separators=(",",":"))+";" for k,v in sorted(d.items()) if v))')"
      fi
      printf '%s|%s|linux|%s|\n' "$config" "$layers" "$arch"
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
fixture_remote_config='{"Cmd":["java"],"Env":["A=B"],"Labels":null,"User":"","AttachStdin":false,"OnBuild":[]}'
reuse_remote_image trade-backend:current >/dev/null
fixture_remote_config='{"Cmd":["java"],"Env":["A=B"]}'
rm "$fixture_dir/tagged"
fixture_remote_config='{"Cmd":["sensitive-fixture-marker"],"Env":["A=B"]}'
if reuse_remote_image trade-backend:current >"$fixture_dir/diagnostic"; then exit 1; fi
grep -q 'image reuse candidates checked:' "$fixture_dir/diagnostic"
if grep -q 'sensitive-fixture-marker' "$fixture_dir/diagnostic"; then exit 1; fi
fixture_remote_config='{"Cmd":["java"],"Env":["A=B"]}'
for different_config in '{"Cmd":["java"],"Env":["A=C"]}' '{"Cmd":["java"],"Env":["A=B"],"User":"different-user"}' '{"Cmd":["java"],"Env":["A=B"],"AttachStdin":true}'; do
  fixture_remote_config="$different_config"
  if reuse_remote_image trade-backend:current >/dev/null; then exit 1; fi
done
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
