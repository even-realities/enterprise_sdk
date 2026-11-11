#!/usr/bin/env bash
set -euo pipefail

# Reject uncommitted build inputs and a changed HEAD before finalizing the package.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
commit="$(git -C "$repo_root" rev-parse --verify HEAD)"
changes="$(git -C "$repo_root" status --porcelain --untracked-files=all)"
if [[ -n "$changes" ]]; then
  printf '%s\n' 'Native release packaging requires a clean source commit.' >&2
  exit 1
fi
if [[ $# -gt 0 && "$1" != "$commit" ]]; then
  printf '%s\n' 'Source commit changed during native release packaging.' >&2
  exit 1
fi
printf '%s\n' "$commit"
