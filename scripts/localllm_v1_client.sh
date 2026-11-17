#!/usr/bin/env bash
# Owns the LocalLLM client master copy and keeps the vendored copies honest.
#
# In LocalLLM:   check-local | manifest | copy <consumer-root>... | check <consumer-root>...
# In a consumer: verify [consumer-root]            (hand edits fail; runs offline)
#                behind <canonical-root> [consumer-root]  (warns when LocalLLM moved on)
#
# Consumers vendor the whole client tree (v1, v2, v3) plus this script, so
# every app carries byte-identical files under one digest.
set -euo pipefail

script_path=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/$(basename -- "$0")
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
canonical_root="$repo_root/client/src/main"
digest_file="$repo_root/client/V1_SOURCE_SHA256"
source_repository="https://github.com/noamvb/local-llm.git"
source_paths=(
  "client/src/main"
  "client/V1_SOURCE_SHA256"
  "app/src/main/aidl/com/noamv/localllm"
  "app/src/main/java/com/noamv/localllm/contract"
  "scripts/localllm_v1_client.sh"
)
vendored_dirs=(
  "aidl/com/noamv/localllm"
  "java/com/noamv/localllm/client"
  "java/com/noamv/localllm/contract"
)

# Digest of the vendored subtrees under a src/main root. For LocalLLM's
# client/src/main this equals the digest of the whole tree, because the tree
# holds nothing else.
tree_digest() {
  local root=$1
  [[ -d "$root" ]] || {
    echo "ERROR: no such source root: $root" >&2
    return 1
  }
  (
    cd "$root" || exit 1
    local dir
    for dir in "${vendored_dirs[@]}"; do
      if [[ -d "$dir" ]]; then find "$dir" \( -type f -o -type l \) -print; fi
    done | LC_ALL=C sort | while IFS= read -r file; do
      if [[ -L "$file" ]]; then
        # A symlink is never part of the canonical tree; hash its target text
        # so adding one changes the digest.
        digest="symlink:$(readlink "$file")"
      else
        digest=$(openssl dgst -sha256 "$file" | awk '{print $NF}')
      fi
      printf '%s  %s\n' "$digest" "$file"
    done
  ) | openssl dgst -sha256 | awk '{print $NF}'
}

manifest_digest() {
  tree_digest "$canonical_root"
}

compare_tree() {
  local source=$1
  local destination=$2
  local label=$3
  if ! diff -qr "$source" "$destination"; then
    echo "ERROR: $label differs from the canonical LocalLLM client." >&2
    return 1
  fi
}

compare_vendored() {
  local source_main=$1
  local destination_main=$2
  local label=$3
  local dir status=0
  for dir in "${vendored_dirs[@]}"; do
    compare_tree "$source_main/$dir" "$destination_main/$dir" "$label $dir" || status=1
  done
  return "$status"
}

provenance_value() {
  awk -F= -v key="$2" '$1 == key { print $2 }' "$1"
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
  echo "Canonical LocalLLM client is synchronized: $actual"
}

require_committed_source() {
  if ! git -C "$repo_root" diff --quiet HEAD -- "${source_paths[@]}"; then
    echo "ERROR: canonical client source or its checked copies differ from HEAD." >&2
    echo "Commit the canonical LocalLLM change before copying it to consumers." >&2
    return 1
  fi

  local untracked
  untracked=$(git -C "$repo_root" ls-files --others --exclude-standard -- "${source_paths[@]}")
  if [[ -n "$untracked" ]]; then
    echo "ERROR: canonical client source contains untracked files:" >&2
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
  for relative in "${vendored_dirs[@]}"; do
    mkdir -p "$destination/$relative"
    rsync -a --delete "$canonical_root/$relative/" "$destination/$relative/"
  done
  mkdir -p "$consumer_root/scripts"
  cp "$script_path" "$consumer_root/scripts/localllm_v1_client.sh"

  local source_commit source_digest
  source_commit=$(git -C "$repo_root" rev-parse HEAD)
  source_digest=$(manifest_digest)
  {
    printf 'source_repository=%s\n' "$source_repository"
    printf 'source_commit=%s\n' "$source_commit"
    printf 'source_sha256=%s\n' "$source_digest"
  } > "$consumer_root/app/localllm-v1-client.provenance"
  echo "Copied canonical client to $consumer_root ($source_digest)"
}

check_consumer() {
  local consumer_root=$1
  check_local
  require_committed_source
  compare_vendored "$canonical_root" "$consumer_root/app/src/main" "$consumer_root"
  cmp -s "$script_path" "$consumer_root/scripts/localllm_v1_client.sh" || {
    echo "ERROR: $consumer_root/scripts/localllm_v1_client.sh differs from the canonical script." >&2
    return 1
  }
  verify_consumer "$consumer_root"
  local recorded_commit
  recorded_commit=$(provenance_value "$consumer_root/app/localllm-v1-client.provenance" source_commit)
  echo "Consumer matches canonical client: $consumer_root (copied at $recorded_commit)"
}

# Offline check for consumer CI: the vendored files must hash to the digest
# recorded when they were copied, so a hand edit fails the build.
verify_consumer() {
  local consumer_root=${1:-$repo_root}
  local provenance="$consumer_root/app/localllm-v1-client.provenance"
  [[ -f "$provenance" ]] || {
    echo "ERROR: missing provenance file: $provenance" >&2
    return 1
  }
  local recorded_repository expected actual
  recorded_repository=$(provenance_value "$provenance" source_repository)
  expected=$(provenance_value "$provenance" source_sha256)
  actual=$(tree_digest "$consumer_root/app/src/main")
  [[ "$recorded_repository" == "$source_repository" ]] || {
    echo "ERROR: $provenance names $recorded_repository, expected $source_repository." >&2
    return 1
  }
  [[ "$(provenance_value "$provenance" source_commit)" =~ ^[0-9a-f]{40}$ ]] || {
    echo "ERROR: $provenance has no valid source_commit (40 hex characters)." >&2
    return 1
  }
  if [[ "$expected" != "$actual" ]]; then
    echo "ERROR: vendored LocalLLM client was edited by hand (hashes to $actual, provenance says $expected)." >&2
    echo "Change it in LocalLLM, then run: scripts/localllm_v1_client.sh copy <this repo>" >&2
    return 1
  fi
  echo "Vendored LocalLLM client is unmodified: $actual"
}

# Non-blocking for consumer CI: compares the vendored copy against a LocalLLM
# checkout (normally a shallow clone of main) and warns when it is behind.
behind_consumer() {
  local canonical_checkout=$1
  local consumer_root=${2:-$repo_root}
  local canonical_digest vendored_digest
  [[ -d "$canonical_checkout/client/src/main" ]] || {
    echo "::warning title=LocalLLM client check skipped::No LocalLLM client at $canonical_checkout/client/src/main; could not compare."
    return 0
  }
  canonical_digest=$(tree_digest "$canonical_checkout/client/src/main")
  vendored_digest=$(tree_digest "$consumer_root/app/src/main")
  if [[ "$canonical_digest" != "$vendored_digest" ]]; then
    echo "::warning title=LocalLLM client behind::Vendored client ($vendored_digest) differs from LocalLLM main ($canonical_digest). Run scripts/localllm_v1_client.sh copy from LocalLLM."
    compare_vendored "$canonical_checkout/client/src/main" "$consumer_root/app/src/main" "vendored" || true
    return 0
  fi
  echo "Vendored LocalLLM client matches LocalLLM main: $vendored_digest"
}

usage() {
  echo "Usage: $0 check-local | manifest | copy <consumer-root>... | check <consumer-root>... | verify [consumer-root] | behind <canonical-root> [consumer-root]" >&2
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
  verify)
    [[ $# -le 2 ]] || usage
    verify_consumer "${2:-}"
    ;;
  behind)
    [[ $# -ge 2 && $# -le 3 ]] || usage
    behind_consumer "$2" "${3:-}"
    ;;
  *) usage ;;
esac
