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

is_elf_file() {
  local file="$1"
  local magic
  magic="$(od -An -tx1 -N4 "$file" 2>/dev/null | tr -d ' \n')"
  [[ "$magic" == "7f454c46" ]]
}

verify_elf_alignment() {
  local file="$1"
  local label="$2"
  local headers="$3"
  local found_load_segment=false

  if ! readelf -lW "$file" >"$headers" 2>/dev/null; then
    echo "$label has ELF headers that could not be read completely." >&2
    return 1
  fi

  while IFS= read -r alignment; do
    found_load_segment=true
    if (( alignment < 0x4000 )); then
      echo "$label has PT_LOAD alignment ${alignment}; 16 KB devices require at least 0x4000." >&2
      return 1
    fi
  done < <(awk '$1 == "LOAD" { print $NF }' "$headers")

  if [[ "$found_load_segment" != true ]]; then
    echo "$label has no inspectable PT_LOAD segments." >&2
    return 1
  fi
}

verify_wrapper_payload() {
  local archive="$1"
  local label="$2"
  local tmp_root="$3"
  local payload_dir
  payload_dir="$(mktemp -d "$tmp_root/payload.XXXXXX")"

  if ! unzip -qq "$archive" -d "$payload_dir"; then
    echo "$label could not be extracted." >&2
    return 1
  fi

  local found_payload_elf=false
  while IFS= read -r payload_file; do
    # readelf accepts Unix static archives and reports their ELF object members.
    # Those archives are not mmap-loaded by Android and have no PT_LOAD contract
    # of their own, so only inspect files whose bytes are actually ELF files.
    if ! is_elf_file "$payload_file"; then
      continue
    fi
    found_payload_elf=true
    found_64_bit_elf=true
    local payload_relative
    payload_relative="${payload_file#"$payload_dir"/}"
    verify_elf_alignment \
      "$payload_file" \
      "$label contains 64-bit ELF ${payload_relative}" \
      "$tmp_root/program-headers.txt" || return 1
  done < <(find "$payload_dir" -type f -print 2>/dev/null | sort)

  if [[ "$found_payload_elf" != true ]]; then
    echo "$label contains no inspectable ELF payload." >&2
    return 1
  fi
}

for apk in "$@"; do
  [[ -f "$apk" ]] || { echo "APK not found: $apk" >&2; exit 1; }
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  unzip -qq "$apk" -d "$tmp"
  found_64_bit_elf=false

  while IFS= read -r so; do
    relative="${so#"$tmp"/lib/}"
    abi="${relative%%/*}"
    [[ "$abi" == "arm64-v8a" || "$abi" == "x86_64" ]] || continue

    if readelf -h "$so" >/dev/null 2>&1; then
      found_64_bit_elf=true
      verify_elf_alignment "$so" "$apk contains 64-bit native library ${relative}" "$tmp/program-headers.txt" || exit 1
      continue
    fi

    if is_wrapper_zip_payload "$so"; then
      verify_wrapper_payload "$so" "$apk contains wrapper payload ${relative}" "$tmp" || exit 1
      continue
    fi

    echo "$apk contains unreadable 64-bit native library ${relative}; refusing to skip it." >&2
    exit 1
  done < <(find "$tmp/lib" -type f -name '*.so' -print 2>/dev/null | sort)

  while IFS= read -r asset_archive; do
    asset_relative="${asset_archive#"$tmp"/assets/youtubedl-android/}"
    abi="${asset_relative%%/*}"
    [[ "$abi" == "arm64-v8a" || "$abi" == "x86_64" ]] || continue

    if ! is_wrapper_zip_payload "$asset_archive"; then
      echo "$apk contains unreadable 64-bit wrapper asset ${asset_relative}; refusing to skip it." >&2
      exit 1
    fi

    verify_wrapper_payload \
      "$asset_archive" \
      "$apk contains wrapper asset ${asset_relative}" \
      "$tmp" || exit 1
  done < <(find "$tmp/assets/youtubedl-android" -type f -name '*.zip.so' -print 2>/dev/null | sort)

  rm -rf "$tmp"
  trap - EXIT
  if [[ "$found_64_bit_elf" != true ]]; then
    echo "$apk contained no inspectable arm64-v8a/x86_64 ELF libraries." >&2
    exit 1
  fi
  echo "16 KB ELF alignment verified: $apk"
done
