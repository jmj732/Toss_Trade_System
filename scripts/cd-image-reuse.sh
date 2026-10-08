#!/usr/bin/env bash
# Caller provides the existing pinned ssh_args and deployment host/user.
reuse_remote_image() {
  local image="$1" family format content fingerprint remote_image remote_fingerprint component_hashes="" component value hash
  family="${image%%:*}"
  [[ "$family" =~ ^trade-(backend|analysis|dashboard)$ ]] || return 1
  # IDs differ across Docker stores. Verify all execution config and ordered
  # uncompressed layer digests, including platform, without exposing env values.
  # A common API schema retains empty/default fields across Engine versions.
  format='{{json .Config}}|{{json .RootFS}}|{{.Os}}|{{.Architecture}}|{{.Variant}}'
  content="$(DOCKER_API_VERSION=1.44 docker image inspect --format "$format" "$image")" || return 1
  [[ -n "$content" ]] || return 1
  fingerprint="$(printf '%s\n' "$content" | sha256sum)" || return 1
  fingerprint="${fingerprint%% *}"
  [[ "$fingerprint" =~ ^[0-9a-f]{64}$ ]] || return 1
  remote_image="$(printf '%q' "$image")"
  remote_fingerprint="$(printf '%q' "$fingerprint")"
  for component in Config RootFS Os Architecture Variant; do
    value="$(DOCKER_API_VERSION=1.44 docker image inspect --format "{{json .$component}}" "$image")" || return 1
    hash="$(printf '%s\n' "$value" | sha256sum)" || return 1
    component_hashes+=" ${hash%% *}"
  done
  # shellcheck disable=SC2029,SC2154
  if ssh "${ssh_args[@]}" "$BACKEND_DEPLOY_USER@$BACKEND_DEPLOY_HOST" \
    "bash -s -- $remote_image $remote_fingerprint $family$component_hashes" <<'REMOTE_REUSE'
set -euo pipefail
image="$1"; expected="$2"; family="$3"
shift 3
component_hashes=("$@")
components=(Config RootFS Os Architecture Variant)
checked=0
format='{{json .Config}}|{{json .RootFS}}|{{.Os}}|{{.Architecture}}|{{.Variant}}'
while IFS= read -r existing; do
  [[ "$existing" == "$family:"* && "$existing" != *'<none>'* ]] || continue
  checked=$((checked + 1))
  content="$(DOCKER_API_VERSION=1.44 docker image inspect --format "$format" "$existing")" || continue
  [[ -n "$content" ]] || continue
  actual="$(printf '%s\n' "$content" | sha256sum)" || continue
  if [[ "${actual%% *}" == "$expected" ]]; then
    docker image tag "$existing" "$image"
    exit 0
  fi
  details=""
  for index in "${!components[@]}"; do
    value="$(DOCKER_API_VERSION=1.44 docker image inspect --format "{{json .${components[$index]}}}" "$existing")" || continue
    hash="$(printf '%s\n' "$value" | sha256sum)" || continue
    match=false
    [[ "${hash%% *}" == "${component_hashes[$index]}" ]] && match=true
    details+=" ${components[$index]}=$match"
  done
  printf 'image content comparison:%s\n' "$details"
done < <(docker image ls --format '{{.Repository}}:{{.Tag}}' "$family")
printf 'image reuse candidates checked: %s\n' "$checked"
exit 1
REMOTE_REUSE
  then
    printf 'reusing verified image content for %s\n' "$image"
    return 0
  fi
  return 1
}
