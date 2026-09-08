"""Execute Kotlin-generated commands in isolated Linux homes; never touch host configuration."""
import base64
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

runtime, toolchain, probe, installer, wrong_target, packages = [
    base64.b64decode(sys.stdin.readline()).decode() for _ in range(6)
]
real_install = sys.stdin.readline().strip() == "1"


def run(command, home, extra=None, limit=25):
    env = dict(os.environ, HOME=str(home), PATH="/usr/sbin:/usr/bin:/sbin:/bin",
               XDG_CONFIG_HOME=str(home / '.config'), XDG_CACHE_HOME=str(home / '.cache'),
               XDG_DATA_HOME=str(home / '.local/share'), NPM_CONFIG_USERCONFIG=str(home / '.npmrc'))
    env.pop("BASH_ENV", None)
    env.pop("ENV", None)
    if extra:
        env.update(extra)
    return subprocess.run(["/bin/bash", "--noprofile", "--norc", "-c", command],
                          cwd=home, env=env, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=limit)


def executable(path, body):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body)
    path.chmod(0o755)


with tempfile.TemporaryDirectory(prefix="kiyori-env-test-") as directory:
    home = Path(directory)
    bindir = home / ".local/bin"
    executable(bindir / "node", '''#!/usr/bin/python3
import os,sys
from pathlib import Path
a=sys.argv[1:]
if a and a[0].endswith('/npm'):
    print(os.environ.get('FAKE_NPM', '12.0.2'))
elif '-e' in a:
    sys.exit(int(os.environ.get('FAKE_NODE_EXIT', '0')))
elif a and a[0].endswith('main.js'):
    print('kiyori-typescript-ok')
else:
    print('v24.20.0')
''')
    # Exercise npm's env shebang lookup with a parent PATH missing ~/.local/bin.
    executable(bindir / "npm", "#!/usr/bin/env node\n")
    executable(bindir / "pnpm", '''#!/usr/bin/python3
import os,sys
if os.environ.get('FAKE_PNPM_WARNING'):
    print('pnpm is running through Node.js because install scripts were skipped', file=sys.stderr)
print('copy' if 'config' in sys.argv else '12.3.4')
''')
    executable(bindir / "tsc", '''#!/usr/bin/python3
import os,sys
from pathlib import Path
if '--version' in sys.argv:
    print('Version 7.0.2')
elif os.environ.get('FAKE_TS_BROKEN'):
    print('panic: bundled: lib.d.ts does not exist', file=sys.stderr)
    sys.exit(2)
else:
    Path('dist').mkdir(exist_ok=True)
    Path('dist/main.js').write_text('compiled')
''')
    executable(bindir / "uv", '''#!/usr/bin/python3
import os,time
if os.environ.get('FAKE_UV_SLOW'): time.sleep(20)
print('uv 1.0.0')
''')
    result = run(runtime, home)
    assert result.returncode == 0 and "12.0.2" in result.stdout, result.stdout
    result = run(runtime, home, {"FAKE_NODE_EXIT": "1"})
    assert result.returncode != 0, result.stdout
    result = run(toolchain, home)
    assert result.returncode == 0 and "__KIYORI_NODE_TOOLCHAIN_READY__" in result.stdout, result.stdout
    for extra in ({"FAKE_TS_BROKEN": "1"}, {"FAKE_PNPM_WARNING": "1"}):
        result = run(toolchain, home, extra)
        assert result.returncode != 0 and "__KIYORI_NODE_TOOLCHAIN_READY__" not in result.stdout, result.stdout
    started = time.monotonic()
    result = run(probe, home, {"FAKE_UV_SLOW": "1"})
    elapsed = time.monotonic() - started
    assert result.returncode == 0, result.stdout
    assert "__KIYORI_ENV_PROBE__:nodejs:1:" in result.stdout, result.stdout
    assert "__KIYORI_ENV_PROBE__:pnpm:1:" in result.stdout, result.stdout
    assert "__KIYORI_ENV_PROBE__:uv:2:" in result.stdout, result.stdout
    assert elapsed < 12, elapsed
    assert not list(home.glob('.kiyori-ts-check.*')), "Probe temporary directory leaked"
    result = run(wrong_target, home)
    assert result.returncode != 0 and not (home / 'forbidden').exists(), result.stdout
    executable(bindir / 'curl', '#!/bin/sh\nexit 22\n')
    executable(bindir / 'tar', '#!/bin/sh\ntouch "$HOME/tar-should-not-run"\nexit 0\n')
    result = run(installer, home)
    assert result.returncode != 0, result.stdout
    assert not (home / 'tar-should-not-run').exists(), "errexit suppressed by outer AND list"
    assert not list((home / '.cache/kiyori').glob('node-stage.*')), "Installer staging directory leaked"
    print('Bash: npm upgrade, env shebang PATH, broken TS, pnpm placeholder, timeout, target mismatch, download failure PASS')

if real_install:
    with tempfile.TemporaryDirectory(prefix='kiyori-env-install-') as directory:
        home = Path(directory)
        result = run(installer, home, limit=150)
        assert result.returncode == 0, result.stdout
        result = run(packages, home, limit=150)
        assert result.returncode == 0, result.stdout
        result = run(toolchain, home)
        assert result.returncode == 0, result.stdout
        # A project installed by pnpm must compile too, not only the global compiler.
        result = run('''set -eu
export PATH="$HOME/.local/bin:$PATH"
cd "$HOME"
printf '%s\n' '{"name":"kiyori-smoke","version":"1.0.0","private":true}' > package.json
pnpm add -D typescript@7.0.2 --registry=https://registry.npmjs.org/
mkdir src
printf '%s\n' 'const value: string = "project-ok"; console.log(value);' > src/main.ts
pnpm exec tsc --ignoreConfig --rootDir src --outDir dist src/main.ts
test "$(node dist/main.js)" = project-ok
printf '%s\n' 'const value: number = "wrong";' > src/bad.ts
if pnpm exec tsc --ignoreConfig --noEmit src/bad.ts > type-error.txt 2>&1; then exit 1; fi
grep 'TS2322' type-error.txt
pnpm config get packageImportMethod
node --version; npm --version; pnpm --version; pnpm exec tsc --version
''', home, limit=90)
        assert result.returncode == 0, result.stdout
        print('Official Node archive + npm/pnpm/TS global and project compile/type-error smoke PASS\n' + result.stdout[-2000:])

print('Environment shell regression PASS')
