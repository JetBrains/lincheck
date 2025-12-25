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
# Updates the project version across gradle.properties files, README.md, docs/v.list and commits the changes.
#
# Usage:
#   ./scripts/update_version.sh <new-version> [--commit]
#
# Example:
#   ./scripts/update_version.sh 3.4.1
#   ./scripts/update_version.sh 3.5-SNAPSHOT --commit
#
# What it changes:
#   - gradle.properties:           version=<version>
#   - common/gradle.properties:    commonVersion=<version>
#   - jvm-agent/gradle.properties: jvmAgentVersion=<version>
#   - trace/gradle.properties:     traceVersion=<version>
#   - README.md:                   testImplementation("org.jetbrains.lincheck:lincheck:<version>")
#   - docs/v.list:                 <var name="lincheckVersion" value="<version>" type="string"/>
#
# Notes:
#   - The script is portable across macOS and Linux (`sed -i` differences handled).
#   - It validates that each target key exists before attempting to modify.
#   - Use `--commit` to actually commit changes to git.

set -euo pipefail

if [[ ${1:-} == "" ]] || [[ ${1:-} == "-h" ]] || [[ ${1:-} == "--help" ]]; then
  echo "Usage: $0 <new-version> [--commit]"
  exit 1
fi

VERSION="$1"
COMMIT=false
if [[ ${2:-} == "--commit" ]]; then
  COMMIT=true
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

update_readme() {
  local file="${repo_root}/README.md"
  if [[ ! -f "$file" ]]; then
    echo -e "Missing file: $file"
    return
  fi
  sed_inplace "s|(testImplementation\(\"org.jetbrains.lincheck:lincheck:).*(\"\))|\\1${VERSION}\\2|" "$file"
  echo -e "Updated $file"
}

update_vlist() {
  local file="${repo_root}/docs/v.list"
  if [[ ! -f "$file" ]]; then
    echo -e "Missing file: $file"
    return
  fi
  sed_inplace "s|(<var name=\"lincheckVersion\" value=\").*(\" type=\"string\"/>)|\\1${VERSION}\\2|" "$file"
  echo -e "Updated $file"
}

update_version "${repo_root}/gradle.properties" "version"
update_version "${repo_root}/common/gradle.properties" "commonVersion"
update_version "${repo_root}/jvm-agent/gradle.properties" "jvmAgentVersion"
update_version "${repo_root}/trace/gradle.properties" "traceVersion"
update_readme
update_vlist

if [[ "$COMMIT" == false ]]; then
  echo -e "\nDry-run complete. No files were committed."
else
  git add gradle.properties common/gradle.properties jvm-agent/gradle.properties trace/gradle.properties README.md docs/v.list
  git commit -m "Release lincheck-${VERSION}"
  echo -e "\nAll done."
fi

