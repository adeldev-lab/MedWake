#!/usr/bin/env bash
set -euo pipefail

if [ $# -ne 2 ]; then
  echo "Usage: ./scripts/release.sh <VERSION_NAME> <VERSION_CODE>"
  echo "Example: ./scripts/release.sh 1.2.3 10203"
  exit 1
fi

NEW_NAME="$1"
NEW_CODE="$2"

sed -i "s/^VERSION_NAME=.*/VERSION_NAME=${NEW_NAME}/" gradle.properties
sed -i "s/^VERSION_CODE=.*/VERSION_CODE=${NEW_CODE}/" gradle.properties

mkdir -p fastlane/metadata/android/en-US/changelogs
CHANGELOG_FILE="fastlane/metadata/android/en-US/changelogs/${NEW_CODE}.txt"
if [ ! -f "$CHANGELOG_FILE" ]; then
  echo "Release v${NEW_NAME}" > "$CHANGELOG_FILE"
  echo "Created changelog stub at $CHANGELOG_FILE"
fi

git add gradle.properties "$CHANGELOG_FILE"
git commit -m "chore(release): bump version to v${NEW_NAME} (${NEW_CODE})"
git tag -a "v${NEW_NAME}" -m "MedWake v${NEW_NAME}"

echo "Tagged v${NEW_NAME}. Run 'git push origin main --tags' to trigger the GitHub Release workflow."
