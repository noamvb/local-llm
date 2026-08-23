#!/usr/bin/env bash
set -euo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
canonical_root="$repo_root/client/src/main"
digest_file="$repo_root/client/V1_SOURCE_SHA256"
source_repository="https://github.com/noamvb/local-llm.git"
source_paths=(
  "client/src/main"
  "client/V1_SOURCE_SHA256"
  "app/src/main/aidl/com/noamv/localllm"
  "app/src/main/java/com/noamv/localllm/contract"
)

manifest_digest() {
  (
    cd "$canonical_root"
    find . -type f -print | LC_ALL=C sort | while IFS= read -r file; do
      digest=$(openssl dgst -sha256 "$file" | awk '{print $NF}')
      printf '%s  %s\n' "$digest" "${file#./}"
    done
  ) | openssl dgst -sha256 | awk '{print $NF}'
}

compare_tree() {
  local source=$1
  local destination=$2
  local label=$3
  if ! diff -qr "$source" "$destination"; then
    echo "ERROR: $label differs from the canonical LocalLLM v1 client." >&2
    return 1
  fi
}

check_local() {
  compare_tree \
    "$canonical_root/aidl/com/noamv/localllm" \
    "$repo_root/app/src/main/aidl/com/noamv/localllm" \
    "app AIDL"
  compare_tree \
    "$canonical_root/java/com/noamv/localllm/contract" \
    "$repo_root/app/src/main/java/com/noamv/localllm/contract" \
    "app contract"

  local expected actual
  expected=$(tr -d '[:space:]' < "$digest_file")
  actual=$(manifest_digest)
  if [[ "$expected" != "$actual" ]]; then
    echo "ERROR: client/V1_SOURCE_SHA256 is stale." >&2
    echo "Expected $expected but canonical src/main hashes to $actual." >&2
    return 1
  fi
  echo "Canonical LocalLLM v1 client is synchronized: $actual"
}

require_committed_source() {
  if ! git -C "$repo_root" diff --quiet HEAD -- "${source_paths[@]}"; then
    echo "ERROR: canonical v1 source or its checked copies differ from HEAD." >&2
    echo "Commit the canonical LocalLLM change before copying it to consumers." >&2
    return 1
  fi

  local untracked
  untracked=$(git -C "$repo_root" ls-files --others --exclude-standard -- "${source_paths[@]}")
  if [[ -n "$untracked" ]]; then
    echo "ERROR: canonical v1 source contains untracked files:" >&2
    printf '%s\n' "$untracked" >&2
    return 1
  fi
}

copy_consumer() {
  local consumer_root=$1
  local destination="$consumer_root/app/src/main"
  [[ -d "$destination" ]] || {
    echo "ERROR: consumer app/src/main does not exist: $consumer_root" >&2
    return 1
  }

  check_local
  require_committed_source

  local relative
  for relative in \
    "aidl/com/noamv/localllm" \
    "java/com/noamv/localllm/client" \
    "java/com/noamv/localllm/contract"; do
    mkdir -p "$destination/$relative"
    rsync -a --delete "$canonical_root/$relative/" "$destination/$relative/"
  done

  local source_commit source_digest
  source_commit=$(git -C "$repo_root" rev-parse HEAD)
  source_digest=$(manifest_digest)
  {
    printf 'source_repository=%s\n' "$source_repository"
    printf 'source_commit=%s\n' "$source_commit"
    printf 'source_sha256=%s\n' "$source_digest"
  } > "$consumer_root/app/localllm-v1-client.provenance"
  echo "Copied canonical v1 client to $consumer_root ($source_digest)"
}

check_consumer() {
  local consumer_root=$1
  local destination="$consumer_root/app/src/main"
  check_local
  require_committed_source
  compare_tree \
    "$canonical_root/aidl/com/noamv/localllm" \
    "$destination/aidl/com/noamv/localllm" \
    "$consumer_root AIDL"
  compare_tree \
    "$canonical_root/java/com/noamv/localllm/client" \
    "$destination/java/com/noamv/localllm/client" \
    "$consumer_root client"
  compare_tree \
    "$canonical_root/java/com/noamv/localllm/contract" \
    "$destination/java/com/noamv/localllm/contract" \
    "$consumer_root contract"

  local provenance="$consumer_root/app/localllm-v1-client.provenance"
  local expected actual recorded_repository recorded_commit current_commit
  [[ -f "$provenance" ]] || {
    echo "ERROR: missing provenance file: $provenance" >&2
    return 1
  }
  expected=$(awk -F= '$1 == "source_sha256" { print $2 }' "$provenance")
  recorded_repository=$(awk -F= '$1 == "source_repository" { print $2 }' "$provenance")
  recorded_commit=$(awk -F= '$1 == "source_commit" { print $2 }' "$provenance")
  current_commit=$(git -C "$repo_root" rev-parse HEAD)
  actual=$(manifest_digest)
  [[ "$recorded_repository" == "$source_repository" ]] || {
    echo "ERROR: $consumer_root provenance repository is not canonical." >&2
    return 1
  }
  [[ "$recorded_commit" == "$current_commit" ]] || {
    echo "ERROR: $consumer_root provenance commit is $recorded_commit, expected $current_commit." >&2
    return 1
  }
  [[ "$expected" == "$actual" ]] || {
    echo "ERROR: $consumer_root provenance digest is stale." >&2
    return 1
  }
  echo "Consumer matches canonical v1 client: $consumer_root ($actual)"
}

usage() {
  echo "Usage: $0 check-local | manifest | copy <consumer-root>... | check <consumer-root>..." >&2
  exit 2
}

command=${1:-}
case "$command" in
  check-local)
    [[ $# -eq 1 ]] || usage
    check_local
    ;;
  manifest)
    [[ $# -eq 1 ]] || usage
    manifest_digest
    ;;
  copy)
    shift
    [[ $# -ge 1 ]] || usage
    for consumer in "$@"; do copy_consumer "$consumer"; done
    ;;
  check)
    shift
    [[ $# -ge 1 ]] || usage
    for consumer in "$@"; do check_consumer "$consumer"; done
    ;;
  *) usage ;;
esac
