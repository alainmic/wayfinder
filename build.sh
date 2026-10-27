#!/bin/bash
# Build helper: redirects Gradle project cache to the VM's ext4 disk.
cd "$(dirname "$0")"
PC="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle"
./gradlew "$@" --console=plain --project-cache-dir="$PC"
