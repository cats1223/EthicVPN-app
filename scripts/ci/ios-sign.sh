#!/usr/bin/env bash
# Signs and exports EthicVPN.ipa on a macOS runner from repository secrets:
#   IOS_P12_BASE64             signing certificate (.p12), base64
#   IOS_P12_PASSWORD           its password (may be empty)
#   IOS_PROFILE_APP_BASE64     .mobileprovision for the app, base64
#   IOS_PROFILE_TUNNEL_BASE64  .mobileprovision for the PacketTunnel extension, base64
# Bundle ids, team and App Group are taken from the profiles, so any explicit App IDs work.
# Run from ios/ after xcodegen. Output: build/EthicVPN.ipa
set -euo pipefail

: "${IOS_P12_BASE64:?}" "${IOS_PROFILE_APP_BASE64:?}" "${IOS_PROFILE_TUNNEL_BASE64:?}"
WORK="$RUNNER_TEMP/signing"
mkdir -p "$WORK" "$HOME/Library/MobileDevice/Provisioning Profiles"

fail() { echo "::error::$*" >&2; exit 1; }
plist() { /usr/libexec/PlistBuddy -c "Print :$2" "$1" 2>/dev/null || true; }

# --- certificate into a throwaway keychain
KEYCHAIN="$WORK/build.keychain-db"
KC_PASS="$(uuidgen)"
echo "$IOS_P12_BASE64" | base64 --decode > "$WORK/cert.p12"
security create-keychain -p "$KC_PASS" "$KEYCHAIN"
security set-keychain-settings -lut 3600 "$KEYCHAIN"
security unlock-keychain -p "$KC_PASS" "$KEYCHAIN"
security import "$WORK/cert.p12" -k "$KEYCHAIN" -P "${IOS_P12_PASSWORD:-}" -T /usr/bin/codesign -T /usr/bin/security
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KC_PASS" "$KEYCHAIN" >/dev/null
security list-keychains -d user -s "$KEYCHAIN" $(security list-keychains -d user | tr -d '"')
IDENTITY="$(security find-identity -v -p codesigning "$KEYCHAIN" | awk 'NR==1 && /\)/ {print $2}')"
[ -n "$IDENTITY" ] || fail "В .p12 нет действующего сертификата подписи (проверьте пароль и срок действия)."
security find-identity -v -p codesigning "$KEYCHAIN"

# --- provisioning profiles
install_profile() { # $1 base64, $2 name -> prints path of decoded plist
    echo "$1" | base64 --decode > "$WORK/$2.mobileprovision"
    security cms -D -i "$WORK/$2.mobileprovision" > "$WORK/$2.plist"
    local uuid; uuid="$(plist "$WORK/$2.plist" UUID)"
    cp "$WORK/$2.mobileprovision" "$HOME/Library/MobileDevice/Provisioning Profiles/$uuid.mobileprovision"
}
install_profile "$IOS_PROFILE_APP_BASE64" app
install_profile "$IOS_PROFILE_TUNNEL_BASE64" tunnel

TEAM="$(plist "$WORK/app.plist" 'TeamIdentifier:0')"
bundle_id() { plist "$WORK/$1.plist" 'Entitlements:application-identifier' | sed "s/^$TEAM\.//"; }
APP_ID="$(bundle_id app)"
TUNNEL_ID="$(bundle_id tunnel)"
APP_UUID="$(plist "$WORK/app.plist" UUID)"
TUNNEL_UUID="$(plist "$WORK/tunnel.plist" UUID)"
GROUP="$(plist "$WORK/app.plist" 'Entitlements:com.apple.security.application-groups:0')"

for p in app tunnel; do
    id="$(bundle_id $p)"
    [[ "$id" != *"*"* ]] || fail "Профиль '$p' wildcard ($id). Network Extension требует отдельный явный App ID для приложения и для расширения."
    plist "$WORK/$p.plist" 'Entitlements:com.apple.developer.networking.networkextension' | grep -q packet-tunnel-provider \
        || fail "В профиле '$p' ($id) нет capability Network Extensions → Packet Tunnel. Без неё VPN на iOS не запустится."
done
[ -n "$GROUP" ] || fail "В профиле приложения нет App Group. Включите App Groups для обоих App ID."
[ "$(plist "$WORK/tunnel.plist" 'Entitlements:com.apple.security.application-groups:0')" = "$GROUP" ] \
    || fail "App Group приложения и расширения различаются."

if [ "$(plist "$WORK/app.plist" ProvisionsAllDevices)" = "true" ]; then METHOD=enterprise
elif [ "$(plist "$WORK/app.plist" 'Entitlements:get-task-allow')" = "true" ]; then METHOD=development
elif [ -n "$(plist "$WORK/app.plist" 'ProvisionedDevices:0')" ]; then METHOD=ad-hoc
else METHOD=app-store
fi
echo "Team $TEAM, app $APP_ID, tunnel $TUNNEL_ID, group $GROUP, export $METHOD"

# --- archive and export
xcodebuild archive -project EthicVPN.xcodeproj -scheme EthicVPN \
    -destination 'generic/platform=iOS' -archivePath build/EthicVPN-signed.xcarchive \
    OTHER_CODE_SIGN_FLAGS="--keychain $KEYCHAIN" \
    CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY="$IDENTITY" DEVELOPMENT_TEAM="$TEAM" \
    APP_BUNDLE_ID="$APP_ID" TUNNEL_BUNDLE_ID="$TUNNEL_ID" APP_GROUP_ID="$GROUP" \
    APP_PROFILE="$APP_UUID" TUNNEL_PROFILE="$TUNNEL_UUID" \
    CURRENT_PROJECT_VERSION="${GITHUB_RUN_NUMBER:-1}" ${APP_VERSION:+MARKETING_VERSION=${APP_VERSION%%-*} APP_DISPLAY_VERSION=$APP_VERSION}

cat > "$WORK/export.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key><string>$METHOD</string>
    <key>teamID</key><string>$TEAM</string>
    <key>signingStyle</key><string>manual</string>
    <key>signingCertificate</key><string>$IDENTITY</string>
    <key>provisioningProfiles</key>
    <dict>
        <key>$APP_ID</key><string>$APP_UUID</string>
        <key>$TUNNEL_ID</key><string>$TUNNEL_UUID</string>
    </dict>
</dict>
</plist>
PLIST
xcodebuild -exportArchive -archivePath build/EthicVPN-signed.xcarchive \
    -exportOptionsPlist "$WORK/export.plist" -exportPath build/signed
mv build/signed/*.ipa build/EthicVPN.ipa
security delete-keychain "$KEYCHAIN"
echo "-> ios/build/EthicVPN.ipa ($METHOD)"
