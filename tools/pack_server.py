#!/usr/bin/env python3
"""Builds the Starwake dedicated-server pack from the packwiz project in pack/.

Usage: python3 tools/pack_server.py <out_dir>             full server pack
       python3 tools/pack_server.py <out_dir> --client    client mods + overrides only (CI boot test)

Downloads every mod the server needs (side "server" or "both", checked against its hash), copies the
pack's overrides (config/, kubejs/) and writes start scripts that install NeoForge on first run.
"""
import glob, hashlib, os, shutil, sys, tomllib, urllib.request

PACK = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'pack')
OVERRIDES = ('config', 'kubejs')


def main(out, side='server'):
    pack = tomllib.load(open(os.path.join(PACK, 'pack.toml'), 'rb'))
    mc, neo = pack['versions']['minecraft'], pack['versions']['neoforge']
    os.makedirs(os.path.join(out, 'mods'), exist_ok=True)
    for f in sorted(glob.glob(os.path.join(PACK, 'mods', '*.pw.toml'))):
        m = tomllib.load(open(f, 'rb'))
        if m.get('side', 'both') not in ('both', side):
            continue
        req = urllib.request.Request(m['download']['url'], headers={'User-Agent': 'starwake-pack-builder'})
        data = urllib.request.urlopen(req).read()
        if hashlib.new(m['download']['hash-format'], data).hexdigest() != m['download']['hash']:
            sys.exit('hash mismatch: ' + m['filename'])
        open(os.path.join(out, 'mods', m['filename']), 'wb').write(data)
        print('mod', m['filename'])
    for d in OVERRIDES:
        src = os.path.join(PACK, d)
        if os.path.isdir(src):
            shutil.copytree(src, os.path.join(out, d), dirs_exist_ok=True)
    if side == 'client':
        return
    installer = f'https://maven.neoforged.net/releases/net/neoforged/neoforge/{neo}/neoforge-{neo}-installer.jar'
    with open(os.path.join(out, 'start.sh'), 'w') as f:
        f.write(f'''#!/usr/bin/env sh
# Starwake {pack["version"]} server (Minecraft {mc}, NeoForge {neo}). Needs Java 21.
cd "$(dirname "$0")"
if [ ! -f run.sh ]; then
  curl -fL -o neoforge-installer.jar {installer} || wget -O neoforge-installer.jar {installer}
  java -jar neoforge-installer.jar --install-server . && rm -f neoforge-installer.jar neoforge-installer.jar.log
fi
[ -f eula.txt ] || echo "eula=false" > eula.txt
grep -q "eula=true" eula.txt || {{ echo "Read https://aka.ms/MinecraftEULA and set eula=true in eula.txt"; exit 1; }}
grep -q "^-Xmx" user_jvm_args.txt 2>/dev/null || printf '\n-Xmx6G\n' >> user_jvm_args.txt
exec sh run.sh nogui
''')
    os.chmod(os.path.join(out, 'start.sh'), 0o755)
    with open(os.path.join(out, 'start.bat'), 'w') as f:
        f.write(f'''@echo off
rem Starwake {pack["version"]} server (Minecraft {mc}, NeoForge {neo}). Needs Java 21.
cd /d "%~dp0"
if not exist run.bat (
  curl -fL -o neoforge-installer.jar {installer}
  java -jar neoforge-installer.jar --install-server .
  del neoforge-installer.jar neoforge-installer.jar.log
)
if not exist eula.txt echo eula=false> eula.txt
findstr /c:"eula=true" eula.txt >nul || (echo Read https://aka.ms/MinecraftEULA and set eula=true in eula.txt & pause & exit /b 1)
findstr /b /c:"-Xmx" user_jvm_args.txt >nul 2>nul || (echo.>> user_jvm_args.txt & echo -Xmx6G>> user_jvm_args.txt)
call run.bat nogui
''')
    with open(os.path.join(out, 'README.txt'), 'w') as f:
        f.write(f'''Starwake {pack["version"]} - dedicated server
Minecraft {mc}, NeoForge {neo}, Java 21.

1. Run start.sh (Linux/macOS) or start.bat (Windows) once: it installs NeoForge and stops at the EULA.
2. Set eula=true in eula.txt, then run the start script again.
3. Memory: edit -Xmx in user_jvm_args.txt (6G default; 8G+ for many players or big airships).
4. Open TCP 25565 for Minecraft and UDP 24454 for Simple Voice Chat.
Players join with the Starwake pack installed in Prism Launcher (same version).
''')


if __name__ == '__main__':
    main(sys.argv[1], 'client' if '--client' in sys.argv[2:] else 'server')
