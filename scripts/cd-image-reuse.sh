#!/usr/bin/env bash
# Caller provides the existing pinned ssh_args and deployment host/user.
reuse_remote_image() {
  local image="$1" config_digest="${2:-}" digest candidate remote_digest remote_image
  digest="$(docker image inspect --format '{{.Id}}' "$image")" || return 1
  [[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]] || return 1
  remote_image="$(printf '%q' "$image")"
  # Containerd image IDs identify manifests; classic Docker IDs identify configs.
  # The optional config digest comes directly from this build's metadata.
  for candidate in "$digest" "$config_digest"; do
    [[ "$candidate" =~ ^sha256:[0-9a-f]{64}$ ]] || continue
    remote_digest="$(printf '%q' "$candidate")"
    # shellcheck disable=SC2029,SC2154
    if ssh "${ssh_args[@]}" "$BACKEND_DEPLOY_USER@$BACKEND_DEPLOY_HOST" \
      "docker image inspect $remote_digest >/dev/null 2>&1 && docker image tag $remote_digest $remote_image"; then
      printf 'reusing exact existing image for %s\n' "$image"
      return 0
    fi
  done
  return 1
}
