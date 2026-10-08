#!/usr/bin/env bash
# Caller provides the existing pinned ssh_args and deployment host/user.
reuse_remote_image() {
  local image="$1" digest remote_digest remote_image
  digest="$(docker image inspect --format '{{.Id}}' "$image")" || return 1
  [[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]] || return 1
  remote_digest="$(printf '%q' "$digest")"
  remote_image="$(printf '%q' "$image")"
  # shellcheck disable=SC2029,SC2154
  if ssh "${ssh_args[@]}" "$BACKEND_DEPLOY_USER@$BACKEND_DEPLOY_HOST" \
    "docker image inspect $remote_digest >/dev/null 2>&1 && docker image tag $remote_digest $remote_image"; then
    printf 'reusing exact existing image for %s\n' "$image"
    return 0
  fi
  return 1
}
