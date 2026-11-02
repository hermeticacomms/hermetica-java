#!/bin/sh
# Maven wrapper: runs the Maven release pinned in .mvn/wrapper/maven-wrapper.properties, downloading it once into
# ~/.m2/wrapper/dists and checking its SHA-512 first. Usage: ./mvnw <maven args>
set -eu
base=$(cd "$(dirname "$0")" && pwd)
props="$base/.mvn/wrapper/maven-wrapper.properties"
prop() { sed -n "s/^$1=//p" "$props" | tr -d '\r'; }
url=$(prop distributionUrl)
sum=$(prop distributionSha512Sum)
name=$(basename "$url" -bin.tar.gz)
home="${MAVEN_USER_HOME:-$HOME/.m2}/wrapper/dists/$name"
if [ ! -x "$home/bin/mvn" ]; then
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  if command -v curl >/dev/null 2>&1; then curl -fsSL -o "$tmp/maven.tar.gz" "$url"; else wget -q -O "$tmp/maven.tar.gz" "$url"; fi
  if command -v sha512sum >/dev/null 2>&1; then got=$(sha512sum "$tmp/maven.tar.gz"); else got=$(shasum -a 512 "$tmp/maven.tar.gz"); fi
  [ "${got%% *}" = "$sum" ] || { echo "mvnw: checksum mismatch for $url" >&2; exit 1; }
  tar -xzf "$tmp/maven.tar.gz" -C "$tmp"
  mkdir -p "$(dirname "$home")"
  rm -rf "$home"
  mv "$tmp/$name" "$home"
fi
exec "$home/bin/mvn" "$@"
