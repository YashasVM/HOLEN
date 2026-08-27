#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -eq 0 ]]; then
  echo "Usage: $0 <apk> [apk...]" >&2
  exit 2
fi

is_wrapper_zip_payload() {
  local file="$1"
  local name
  name="$(basename "$file")"
  [[ "$name" == *.zip.so ]] || return 1

  local magic
  magic="$(od -An -tx1 -N4 "$file" 2>/dev/null | tr -d ' \n')"
  [[ "$magic" == "504b0304" || "$magic" == "504b0506" || "$magic" == "504b0708" ]]
}

for apk in "$@"; do
  [[ -f "$apk" ]] || { echo "APK not found: $apk" >&2; exit 1; }
  tmp="$(mktemp -d)"
  unzip -qq "$apk" 'lib/*/*.so' -d "$tmp"
  found_64_bit_elf=false

  while IFS= read -r so; do
    relative="${so#"$tmp"/lib/}"
    abi="${relative%%/*}"
    [[ "$abi" == "arm64-v8a" || "$abi" == "x86_64" ]] || continue

    if ! readelf -h "$so" >/dev/null 2>&1; then
      if is_wrapper_zip_payload "$so"; then
        continue
      fi
      echo "$apk contains unreadable 64-bit native library ${relative}; refusing to skip it." >&2
      rm -rf "$tmp"
      exit 1
    fi

    found_64_bit_elf=true
    found_load_segment=false
    headers="$tmp/program-headers.txt"

    if ! readelf -lW "$so" >"$headers" 2>/dev/null; then
      echo "$apk contains 64-bit native library ${relative} whose program headers could not be read completely." >&2
      rm -rf "$tmp"
      exit 1
    fi

    while IFS= read -r alignment; do
      found_load_segment=true
      if (( alignment < 0x4000 )); then
        echo "$apk contains 64-bit native library ${relative} with PT_LOAD alignment ${alignment}; 16 KB devices require at least 0x4000." >&2
        rm -rf "$tmp"
        exit 1
      fi
    done < <(awk '$1 == "LOAD" { print $NF }' "$headers")

    if [[ "$found_load_segment" != true ]]; then
      echo "$apk contains 64-bit native library ${relative} with no inspectable PT_LOAD segments." >&2
      rm -rf "$tmp"
      exit 1
    fi
  done < <(find "$tmp/lib" -type f -name '*.so' -print 2>/dev/null | sort)

  rm -rf "$tmp"
  if [[ "$found_64_bit_elf" != true ]]; then
    echo "$apk contained no inspectable arm64-v8a/x86_64 ELF libraries." >&2
    exit 1
  fi
  echo "16 KB ELF alignment verified: $apk"
done
