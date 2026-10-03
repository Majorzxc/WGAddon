#!/usr/bin/env bash
# Интеграционный тест: настоящий Paper + WorldEdit + WorldGuard + WGRegionList.
# Сервер управляется через консоль (stdin), результаты проверяются по логу и файлам.
set -uo pipefail

RUN=${1:-run}
LOG="$RUN/server.log"
FILE="$RUN/plugins/WGRegionList/regions/main/test.yml"
WG_FILE="$RUN/plugins/WorldGuard/worlds/world/regions.yml"
FAILED=0

pass() { echo "✔ $*"; }
fail() { echo "::error::✘ $*"; FAILED=1; }
check() { if eval "$1"; then pass "$2"; else fail "$2"; fi; }

# ── подготовка сервера
cp -r .github/server-test/plugins/. "$RUN/plugins/"
cp build/libs/WGRegionList-*.jar "$RUN/plugins/"
echo "eula=true" > "$RUN/eula.txt"
cat > "$RUN/server.properties" <<'EOF'
online-mode=false
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
view-distance=3
simulation-distance=3
EOF

mkfifo "$RUN/console"
(cd "$RUN" && java -Xms1G -Xmx2G -jar paper.jar --nogui < console > server.log 2>&1; echo $? > exit-code) &
exec 3> "$RUN/console"

send() { echo ">>> $*"; echo "$*" >&3; }
wait_for() {
  for ((i = 0; i < $2; i++)); do
    grep -qF -- "$1" "$LOG" 2>/dev/null && return 0
    [ -f "$RUN/exit-code" ] && return 1
    sleep 1
  done
  return 1
}
mark() { send "say MARK-$1"; wait_for "MARK-$1" 30; }
section() { sed -n "/MARK-$1/,/MARK-$2/p" "$LOG"; }

if ! wait_for "Done (" 900; then
  tail -n 200 "$LOG"
  echo "::error::сервер не запустился"
  exit 1
fi
sleep 3

echo "──── лог WGRegionList при запуске"
grep -a "WGRegionList" "$LOG" | head -n 40
echo "──── ошибки при запуске (WorldEdit/WorldGuard/WGRegionList)"
grep -anE "ERROR|Exception|Could not load" "$LOG" | head -n 30
check 'grep -aq "Enabling WorldGuard" "$LOG" && ! grep -aq "WorldGuard не включён" "$LOG"' "WorldGuard запустился"

# ── 1. загрузка
check 'grep -aq "\[world\] регионов аддона: 4 (файлов: 2)" "$LOG"' "загружено 4 региона из 2 файлов"
check 'grep -aq "восстановлено связей с родителями: 1" "$LOG"' "восстановлена связь native_kid -> test_spawn"

mark 1; send "rg info test_spawn -w world"; sleep 3; mark 2
check 'section 1 2 | grep -aq "priority=10"' "/rg info видит регион аддона test_spawn"
mark 3; send "rg info native_kid -w world"; sleep 3; mark 4
check 'section 3 4 | grep -aq "test_spawn"' "родитель native_kid (регион WorldGuard) — test_spawn из аддона"
mark 5; send "rg info test_nested -w world"; sleep 3; mark 6
check 'section 5 6 | grep -aq "native_root"' "родитель test_nested (аддон) — native_root из WorldGuard"

# ── 2. изменение флага в игре сохраняется в файл аддона
send "rg flag test_spawn -w world greeting Hello from game"; sleep 2
send "wgrl save"; sleep 3
check 'grep -aq "Hello from game" "$FILE"' "флаг из игры записан в файл аддона"
check 'grep -aq "priority: 10 # не трогать" "$FILE"' "комментарии файла сохранены"

# ── 3. /rg redefine -g
send "rg redefine -g test_redef -w world"; sleep 3
send "wgrl save"; sleep 3
check 'grep -aA3 "test_redef:" "$FILE" | grep -aq "type: global"' "/rg redefine сохранён в файл аддона"

# ── 4. /rg remove
send "rg remove test_child -w world"; sleep 3
send "wgrl save"; sleep 3
check '! grep -aq "test_child:" "$FILE"' "/rg remove удалил регион из файла аддона"

# ── 5. хранилище WorldGuard не содержит регионов аддона
send "rg save -w world"; sleep 4
check '! grep -aqE "^\s+test_(spawn|redef|nested):" "$WG_FILE"' "regions.yml WorldGuard не содержит регионов аддона"
check 'grep -aq "native_kid" "$WG_FILE" && grep -aq "parent: test_spawn" "$WG_FILE"' "связь native_kid -> test_spawn сохранена WorldGuard"

# ── 6. /wg reload
mark 7; send "wg reload"; sleep 6; send "rg info test_spawn -w world"; sleep 3; mark 8
check 'section 7 8 | grep -aq "priority=10" && ! section 7 8 | grep -aq "No region could be found"' "регионы аддона на месте после /wg reload"

# ── 7. /wgrl reload
mark 9; send "wgrl reload"; sleep 4; mark 10
check 'section 9 10 | grep -aq "Регионы перезагружены"' "/wgrl reload"

# ── 8. изменения сохраняются при остановке сервера
send "rg flag test_spawn -w world pvp allow"
send "stop"
for ((i = 0; i < 180; i++)); do [ -f "$RUN/exit-code" ] && break; sleep 1; done
exec 3>&-
check '[ -f "$RUN/exit-code" ]' "сервер остановился"
check 'grep -aq "pvp: allow" "$FILE"' "изменение сохранено при остановке"

# ── 9. ошибки плагина в логе
if grep -anE "Exception|Error|SEVERE|ERROR" "$LOG" | grep -aiE "wgregionlist|majorzxc"; then
  fail "в логе есть ошибки WGRegionList"
else
  pass "ошибок WGRegionList в логе нет"
fi

echo "──── файл аддона после теста"; cat "$FILE"
echo "──── regions.yml WorldGuard после теста"; cat "$WG_FILE"
echo "──── хвост лога"; tail -n 60 "$LOG"
exit $FAILED
