#!/bin/bash

#
# Lincheck
#
# Copyright (C) 2019 - 2025 JetBrains s.r.o.
#
# This Source Code Form is subject to the terms of the
# Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
# with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
#
# Updates the project version across 4 gradle.properties files and commits the changes.
#
# Usage:
#   ./scripts/update_version_for_trace_modules.sh <new-version> [--dry-run]
#
# Example:
#   ./scripts/update_version_for_trace_modules.sh 3.4.1
#   ./scripts/update_version_for_trace_modules.sh 3.5-SNAPSHOT --dry-run
#
# What it changes:
#   - gradle.properties:           version=
#   - common/gradle.properties:    commonVersion=
#   - jvm-agent/gradle.properties: jvmAgentVersion=
#   - trace/gradle.properties:     traceVersion=
#
# Notes:
#   - The script is portable across macOS and Linux (`sed -i` differences handled).
#   - It validates that each target key exists before attempting to modify.
#   - Use `--dry-run` to preview changes before commit.

set -euo pipefail

if [[ ${1:-} == "" ]] || [[ ${1:-} == "-h" ]] || [[ ${1:-} == "--help" ]]; then
  echo "Usage: $0 <new-version> [--dry-run]"
  exit 1
fi

VERSION="$1"
DRY_RUN=false
if [[ ${2:-} == "--dry-run" ]]; then
  DRY_RUN=true
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

sed_inplace() {
  # Usage: sed_inplace <sed-script> <file>
  local sed_script="$1"
  local file="$2"
  if [[ "$(uname)" == "Darwin" ]]; then
    sed -E -i '' "$sed_script" "$file"
  else
    sed -E -i "$sed_script" "$file"
  fi
}

update_version() {
  local file="$1"
  local version_name="$2"

  if [[ ! -f "$file" ]]; then
      echo -e "Missing file: $file"
      return
    fi
    if ! grep -q "^${version_name}=" "$file"; then
      echo -e "Key not found: ${version_name} in $file"
      return
    fi

    sed_inplace "s|^(${version_name}=).*|\\1${VERSION}|" "$file"
    echo -e "Updated $file: ${version_name}=$(grep "^${version_name}=" "$file" | cut -d'=' -f2-)"
}

update_version "${repo_root}/gradle.properties" "version"
update_version "${repo_root}/common/gradle.properties" "commonVersion"
update_version "${repo_root}/jvm-agent/gradle.properties" "jvmAgentVersion"
update_version "${repo_root}/trace/gradle.properties" "traceVersion"

if [[ "$DRY_RUN" == true ]]; then
  echo -e "\nDry-run complete. No files were committed."
else
  git add gradle.properties common/gradle.properties jvm-agent/gradle.properties trace/gradle.properties
  git commit -m "Prepare minor release ${VERSION}"
  echo -e "\nAll done."
fi

