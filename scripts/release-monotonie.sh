#!/usr/bin/env bash
# Monotonie-Prüfung für Releases (ADR 0004, Nachtrag 20.10.2026).
#
# Aufruf: release-monotonie.sh <Version ohne v> < Liste der veröffentlichten Tags (ein Tag je Zeile)
#
# Rechnet die neue Version und jeden Tag mit derselben Formel wie EmberlightVersion in einen versionCode um
# und prüft, ob die neue Version strikt größer ist als alle bisherigen. Tags, die nicht dem Schema
# vX.Y.Z bzw. vX.Y.Z-(alpha|beta|rc).N entsprechen, werden ignoriert.
#
# Alt-Tags: v0.1.0 und v0.2.0 wurden nach dem alten Schema mit den Codes 100 bzw. 200 veröffentlicht. Für den
# Vergleich gilt die neue Formel, also 10099 bzw. 20099. Das ist richtig, weil jede neue Version darüber liegen soll.
#
# Rückgabe: 0 neue Version ist größer, 1 nicht größer, 2 Aufruf- oder Eingabefehler.
# Ausgabe (stdout): eine Zeile mit neuem Code, höchstem bisherigen Vergleichswert und Tag.
set -euo pipefail

# Dieselbe Syntax wie in release.yml. Gruppen: 1 Major, 2 Minor, 3 Patch, 5 Vorab-Art, 6 Vorab-Nummer.
re='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(alpha|beta|rc)\.([1-9]|1[0-9]))?$'

# code_of <Version ohne v>: gibt den versionCode aus, Rückgabe 1 bei ungültiger Version.
code_of() {
  local v=$1
  if [[ ! "$v" =~ $re ]]; then
    return 1
  fi
  local major=${BASH_REMATCH[1]} minor=${BASH_REMATCH[2]} patch=${BASH_REMATCH[3]}
  local label=${BASH_REMATCH[5]:-} number=${BASH_REMATCH[6]:-0}
  # Längenprüfung vor dem Rechnen, wie im Workflow. Grenzen wie EmberlightVersion (X höchstens 2099, Y und Z höchstens 99).
  if (( ${#major} > 4 || major > 2099 || ${#minor} > 2 || minor > 99 || ${#patch} > 2 || patch > 99 )); then
    return 1
  fi
  local stage=99
  case "$label" in
    alpha) stage=$(( number )) ;;
    beta) stage=$(( 20 + number )) ;;
    rc) stage=$(( 40 + number )) ;;
  esac
  echo $(( (major * 10000 + minor * 100 + patch) * 100 + stage ))
}

if (( $# != 1 )); then
  echo "Aufruf: $0 <Version ohne v>" >&2
  exit 2
fi
new=$1
if ! new_code=$(code_of "$new"); then
  echo "Ungültige Version '${new}' für die Monotonie-Prüfung." >&2
  exit 2
fi

max=-1
max_tag="-"
while IFS= read -r tag; do
  # Nur Tags mit führendem v. Alles andere (z. B. Entwurfs-Namen) ist kein Release im Schema.
  [[ "$tag" == v* ]] || continue
  if ! code=$(code_of "${tag#v}"); then
    continue
  fi
  if (( code > max )); then
    max=$code
    max_tag=$tag
  fi
done

if (( max < 0 )); then
  echo "neu=${new_code} bisher=keines (kein veröffentlichtes Release im Schema)"
  exit 0
fi
echo "neu=${new_code} bisher=${max} (${max_tag})"
if (( new_code > max )); then
  exit 0
fi
exit 1
