#!/usr/bin/env bash
set -euo pipefail
ASSETS="AmbiX/app/src/main/assets/card_templates"
mkdir -p "$ASSETS"
base="https://raw.githubusercontent.com/seanzain0007/ClashRoyale_Card_Images_New/main"
for card in "Princess" "Prince" "Goblin Barrel" "The Log" "Guards" "Rocket" "Bandit" "Rascals"; do
  file="$(printf '%s' "$card" | sed 's/ /%20/g').png"
  curl --fail --location --silent --show-error "$base/$file" -o "$ASSETS/$card.png"
  test -s "$ASSETS/$card.png"
done
echo "Downloaded 8 known-deck card reference images."
