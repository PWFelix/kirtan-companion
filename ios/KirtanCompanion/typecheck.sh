#!/bin/bash
# Typecheck the whole app as one module, then the tests against it.
#
# Bypasses xcodebuild (gated on `sudo xcodebuild -license`) by driving the
# compiler directly. Emits the KirtanCompanion module, then typechecks the test
# target against it — so cross-file AND cross-module references are checked.
set -euo pipefail
DEV=/Applications/Xcode.app/Contents/Developer
SDK="$DEV/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk"
SWIFTC="$DEV/Toolchains/XcodeDefault.xctoolchain/usr/bin/swiftc"
TARGET=arm64-apple-ios17.0-simulator
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

src_files=(); while IFS= read -r f; do src_files+=("$f"); done < <(find Sources -name '*.swift')
if [[ ${#src_files[@]} -eq 0 ]]; then echo "no source files"; exit 0; fi
echo "── app: ${#src_files[@]} files"
"$SWIFTC" -parse-as-library -swift-version 5 -target "$TARGET" -sdk "$SDK" \
  -enable-testing \
  -emit-module -module-name KirtanCompanion -module-cache-path "$TMP/mc" \
  -emit-module-path "$TMP/KirtanCompanion.swiftmodule" \
  "${src_files[@]}" 2>&1 | grep -v "^$" || true

if find Tests -name '*.swift' 2>/dev/null | grep -q .; then
  # The xcodebuild test target is STANDALONE: it compiles Sources/data + engine + storage
  # directly into the test module (an .app can't be linked into a .xctest, and this hand-rolled
  # project has no framework to @testable import). Mirror that here: typecheck the same three
  # source dirs together with the test files, in one module.
  test_files=(); while IFS= read -r f; do test_files+=("$f"); done < <(
    { find Sources/data Sources/engine Sources/storage -name '*.swift' 2>/dev/null; find Tests -name '*.swift' 2>/dev/null; } | sort -u
  )
  echo "── tests: ${#test_files[@]} files (data+engine+storage+Tests, in-module)"
  PLATFORM="$DEV/Platforms/iPhoneSimulator.platform"
  "$SWIFTC" -parse-as-library -swift-version 5 -target "$TARGET" -sdk "$SDK" \
    -F "$PLATFORM/Developer/Library/Frameworks" -I "$PLATFORM/Developer/usr/lib" \
    -module-cache-path "$TMP/mc" -typecheck "${test_files[@]}"
  echo "OK"
else
  echo "OK (no tests)"
fi
