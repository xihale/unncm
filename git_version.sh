#!/usr/bin/env bash

set -euo pipefail

if ! git rev-parse --git-dir >/dev/null 2>&1; then
  echo "0.0.0-unknown"
  exit 0
fi

tag="$(git describe --tags --abbrev=0 2>/dev/null || true)"
if [[ -z "${tag}" ]]; then
  tag="0.0.0"
fi

short_hash="$(git rev-parse --short HEAD 2>/dev/null || true)"
if [[ -z "${short_hash}" ]]; then
  short_hash="unknown"
fi

if [[ "${tag}" == "0.0.0" ]]; then
  distance="$(git rev-list --count HEAD 2>/dev/null || echo 0)"
else
  distance="$(git rev-list --count "${tag}..HEAD" 2>/dev/null || echo 0)"
fi

dirty_suffix=""
if [[ -n "$(git status --porcelain 2>/dev/null)" ]]; then
  dirty_suffix="-dirty"
fi

if [[ "${distance}" == "0" && "${tag}" != "0.0.0" ]]; then
  printf '%s%s\n' "${tag}" "${dirty_suffix}"
else
  printf '%s-%s-g%s%s\n' "${tag}" "${distance}" "${short_hash}" "${dirty_suffix}"
fi
