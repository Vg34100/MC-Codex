#!/usr/bin/env python3
"""Monitor production clients; Loom/PortableMC own installation and launching."""
import hashlib
import io
import ntpath
import os
from pathlib import Path
import re
import signal
import subprocess
import tarfile
import tempfile
import time
import urllib.parse
import urllib.request
from zipfile import ZipFile


PORTABLEMC_VERSION = '5.0.5'
PORTABLEMC_SHA256 = 'c1ad577e9441040e65a55b521c6ab0fc4c32ec0c6f114d169ae6eee7d3d9fda6'
LINUX_PORTABLEMC_SHA256 = '272611ab554237993cd2720dcc7219516f5e9d6382986df2c6d7177793b145dc'


def windows_path(path):
    """Translate a native Windows path only when running under WSL."""
    if os.name == 'nt':
        return Path(path)
    return Path(subprocess.check_output(['wslpath', '-u', path], text=True, stderr=subprocess.PIPE, timeout=10).strip())


def neoforge_paths(target, token):
    if os.name != 'nt':
        root = Path(tempfile.gettempdir()) / 'codex-release-smoke' / target / token
        executable = Path(tempfile.gettempdir()) / 'codex-tools' / 'portablemc' / PORTABLEMC_VERSION / 'portablemc'
        return root / 'client', str(root), executable
    appdata, temporary = os.environ['LOCALAPPDATA'], os.environ['TEMP']
    root = ntpath.join(temporary, 'codex-release-smoke', target, token)
    executable = windows_path(ntpath.join(appdata, 'Codex', 'tools', 'portablemc', PORTABLEMC_VERSION, 'portablemc.exe'))
    return windows_path(ntpath.join(root, 'client')), root, executable


def portablemc_binary(archive):
    """Read-only verification shared by smoke, doctor and bootstrap."""
    payload = archive.read_bytes()
    expected = LINUX_PORTABLEMC_SHA256 if archive.name.endswith('.tar.gz') else PORTABLEMC_SHA256
    if hashlib.sha256(payload).hexdigest() != expected:
        raise RuntimeError(f'PortableMC cached archive checksum mismatch: {archive}')
    if archive.name.endswith('.tar.gz'):
        with tarfile.open(fileobj=io.BytesIO(payload)) as tar:
            return tar.extractfile(f'portablemc-{PORTABLEMC_VERSION}-linux-x86_64-gnu/portablemc').read()
    with ZipFile(io.BytesIO(payload)) as zip_file:
        return zip_file.read('portablemc.exe')


def verify_portablemc(executable):
    binary = portablemc_binary(executable.with_name('portablemc.zip' if os.name == 'nt' else 'portablemc.tar.gz'))
    if executable.read_bytes() != binary:
        raise RuntimeError(f'PortableMC cached executable differs from verified archive: {executable}')
    version = subprocess.check_output(launcher_prefix(executable) + ['--version'], text=True, stderr=subprocess.PIPE, timeout=15)
    if not version.startswith(f'portablemc {PORTABLEMC_VERSION}\n'):
        raise RuntimeError('Unexpected PortableMC version (expected ' + PORTABLEMC_VERSION + ')')


def acquire_portablemc(executable):
    archive = executable.with_name('portablemc.zip' if os.name == 'nt' else 'portablemc.tar.gz')
    executable.parent.mkdir(parents=True, exist_ok=True)
    if not archive.is_file():
        platform_asset = 'windows-x86_64-msvc.zip' if os.name == 'nt' else 'linux-x86_64-gnu.tar.gz'
        url = (f'https://github.com/theorzr/portablemc/releases/download/v{PORTABLEMC_VERSION}/'
               f'portablemc-{PORTABLEMC_VERSION}-{platform_asset}')
        print(f'Downloading official PortableMC {PORTABLEMC_VERSION}: {url}', flush=True)
        with urllib.request.urlopen(url, timeout=60) as response:
            payload = response.read()
        expected = PORTABLEMC_SHA256 if os.name == 'nt' else LINUX_PORTABLEMC_SHA256
        if hashlib.sha256(payload).hexdigest() != expected:
            raise RuntimeError('PortableMC download checksum mismatch')
        archive.write_bytes(payload)
    binary = portablemc_binary(archive)
    if not executable.is_file():
        executable.write_bytes(binary)
    if os.name != 'nt' and not os.access(executable, os.X_OK):
        executable.chmod(executable.stat().st_mode | 0o111)
    verify_portablemc(executable)
    print(f'PortableMC {PORTABLEMC_VERSION}: verified cached executable {executable}', flush=True)


def launcher_prefix(executable):
    # Optional local test runtime; no system libraries or mod dependencies change.
    library_dir = os.environ.get('BUILD_SMART_PORTABLEMC_LIBRARY_DIR')
    if os.name != 'nt' and library_dir:
        return [str(Path(library_dir) / 'ld-linux-x86-64.so.2'), '--library-path', library_dir, str(executable)]
    return [str(executable)]


def neoforge_command(executable, root, version, legacy, loader='neoforge', minecraft=None):
    join = ntpath.join if os.name == 'nt' else os.path.join
    main = os.environ.get('BUILD_SMART_RELEASE_MAIN_DIR', join(root, 'main'))
    return launcher_prefix(executable) + ['start', '--main-dir', main,
            '--mc-dir', join(root, 'client'), '--jvm-policy', 'mojang',
            '--username', 'ReleaseSmoke', '--jvm-arg=-Xmx2G',
            '--jvm-arg=-Xlog:' + ('class*' if legacy else 'class+load') + '=info:file=class-load-%p.log',
            '-vv', ('neoforge::' + version if loader == 'neoforge' else
                    f'forge::{minecraft}-{version}' if loader == 'forge' else f'fabric:{minecraft}:{version}')]


def staged_release(run_dir, artifact, sha256):
    jars = list((run_dir / 'mods').glob('*.jar'))
    mod_jars = []
    for jar in jars:
        with ZipFile(jar) as zip_file:
            if 'net/vg/codex/ModMain.class' in zip_file.namelist():
                mod_jars.append(jar)
    expected = run_dir / 'mods' / artifact.name
    if mod_jars != [expected] or hashlib.sha256(expected.read_bytes()).hexdigest() != sha256:
        raise RuntimeError('Staged release artifact missing, ambiguous, or changed')
    return ', '.join(sorted(jar.name for jar in jars))


def run(command, run_dir, artifact, project_root, legacy=False, neoforge=None, log_dir=None):
    run_dir = Path(run_dir)
    if neoforge:
        log_dir = Path(log_dir)
        # On native Windows the client is a child of this fresh log directory.
        log_dir.mkdir(parents=True, exist_ok=False)
    run_dir.mkdir(parents=True, exist_ok=False)
    sha256 = hashlib.sha256(artifact.read_bytes()).hexdigest()
    print(f"Testing release JAR: {artifact} | {artifact.stat().st_size} bytes | SHA-256 {sha256}", flush=True)
    print(f"{'PortableMC' if neoforge else 'Loom'} production client; isolated directory: {run_dir}", flush=True)
    dependencies = ''
    neo_loader = bool(neoforge and neoforge[3] in ('neoforge', 'forge'))
    if neoforge:
        root, executable, version, loader, minecraft = neoforge
        try:
            acquire_portablemc(executable)
            launch = neoforge_command(executable, root, version, legacy, loader, minecraft)
            with (log_dir / 'install.log').open('w') as output:
                subprocess.run(launch + ['--dry'], stdout=output, stderr=subprocess.STDOUT, check=True,
                               timeout=float(os.environ.get('BUILD_SMART_RELEASE_INSTALL_TIMEOUT', '600')))
            with (log_dir / 'stage.log').open('w') as output:
                subprocess.run(command, cwd=project_root, stdout=output, stderr=subprocess.STDOUT, check=True, timeout=300)
            dependencies = staged_release(run_dir, artifact, sha256)
            print(f'Staged runtime JARs: {dependencies}', flush=True)
            command = launch
        except (OSError, RuntimeError, subprocess.SubprocessError) as error:
            print(f'FAIL: {error}; retained installation/staging logs: {log_dir}', flush=True)
            return False, str(error)
    timeout = float(os.environ.get('BUILD_SMART_RELEASE_READY_TIMEOUT', '180'))
    started = time.monotonic()
    origin = None
    pid = None
    stopped = False
    first_screen_at = None
    log = (Path(log_dir) / 'portablemc.log') if neoforge else run_dir / 'gradle.log'
    fatal = re.compile(r'Incompatible mods found|Mixin apply .*failed|MixinApplyError|InvalidMixinException|'
                       r'InjectionError|ModResolutionException|Exception in thread|Crash report saved|\bFATAL\b|'
                       r'ModLoadingException|ModLoadingFailedException|Loading errors encountered|'
                       r'Mixin transformation of .* failed|BootstrapMethodError|Failed to reload resource packs', re.I)
    with log.open('w') as output:
        process = subprocess.Popen(command, cwd=project_root, stdout=output, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
        try:
            while process.poll() is None:
                text = log.read_text(errors='replace')
                latest = run_dir / 'logs/latest.log'
                runtime = latest.read_text(errors='replace') if latest.is_file() else ''
                match = fatal.search(text + runtime)
                if match:
                    raise RuntimeError(f'Fatal startup error: {match[0]}')
                if list((run_dir / 'crash-reports').glob('*')):
                    raise RuntimeError('Minecraft produced a crash report')
                class_logs = list(run_dir.glob('class-load-*.log'))
                if len(class_logs) > 1:
                    raise RuntimeError('Multiple client JVMs/logs in isolated run')
                if class_logs:
                    pid = int(re.fullmatch(r'class-load-(\d+)\.log', class_logs[0].name)[1])
                    classes = class_logs[0].read_text(errors='replace')
                    if "Initializing 'net/minecraftforge/client/gui/LoadingErrorScreen'" in classes:
                        raise RuntimeError('Forge displayed its loading error screen')
                    for line in classes.splitlines():
                        if 'net.vg.codex.ModMain source:' in line:
                            origin = line
                            expected = run_dir / 'mods' / artifact.name
                            source = urllib.parse.urlparse(line.split(' source: ', 1)[1])
                            source_path = urllib.parse.unquote(source.path)
                            if neo_loader and legacy and source.scheme == 'union':
                                # SecureJarHandler's archive URL adds its filesystem
                                # ID; the underlying file must still be this exact JAR.
                                archive = re.fullmatch(r'(.+\.jar)#\d+!/', source_path)
                                if not archive:
                                    raise RuntimeError(f'Unrecognized legacy JAR origin: {line}')
                                source_path = archive[1]
                            elif source.scheme != 'file':
                                raise RuntimeError(f'Wrong Codex code origin: {line}')
                            loaded = windows_path(source_path.lstrip('/')) if os.name == 'nt' else Path(source_path)
                            if not loaded.samefile(expected):
                                raise RuntimeError(f'Wrong Codex code origin: {line}')
                options = run_dir / 'options.txt'
                marker = 'startedCleanly:true' if options.is_file() and 'startedCleanly:true' in options.read_text() else None
                screen = 'net/minecraft/client/gui/screens/AccessibilityOnboardingScreen' if neo_loader else 'net/minecraft/class_8032'
                if legacy and class_logs and f"Initializing '{screen}'" in classes:
                    # 1.21.1's onGameLoadFinished creates the first-run screen
                    # after finishReload; it has no startedCleanly option.
                    first_screen_at = first_screen_at or time.monotonic()
                    if 'Reloading ResourceManager' in text + runtime and 'atlas' in text + runtime and time.monotonic() - first_screen_at >= 3:
                        marker = 'initial resource reload + first-screen initialization (3s stable)'
                production = not neo_loader or ('CLIENT in PROD' in text + runtime if not legacy
                                              else "Launching target 'forgeclient'" in text + runtime)
                if origin and marker and production:
                    # This is saved at the end of vanilla onGameLoadFinished,
                    # after the initial resource reload and first-screen setup.
                    dependencies = staged_release(run_dir, artifact, sha256)
                    if hashlib.sha256(artifact.read_bytes()).hexdigest() != sha256:
                        raise RuntimeError('Release artifact changed during smoke')
                    print(origin, flush=True)
                    print(f'Startup marker: {marker}', flush=True)
                    if os.name == 'nt':
                        subprocess.run(['taskkill' if os.name == 'nt' else 'taskkill.exe', '/PID', str(pid)],
                                       check=True, capture_output=True, timeout=15)
                    else:
                        os.kill(pid, signal.SIGTERM)  # Owned game JVM; no GUI automation.
                    stopped = True
                    process.wait(timeout=20)
                    final = log.read_text(errors='replace')
                    if fatal.search(final):
                        raise RuntimeError('Fatal error during shutdown')
                    # SIGTERM gives Java 143 and Loom reports that expected exit.
                    expected_termination = os.name != 'nt' and (process.returncode in (-15, 143)
                            or 'non-zero exit value 143' in final or 'returncode=143' in final or 'exit code 143' in final)
                    if process.returncode != 0 and not expected_termination:
                        raise RuntimeError(f'Unexpected production-task exit: {process.returncode}')
                    evidence = f'JAR: {artifact}\nSHA-256: {sha256}\n{origin}\nStartup: {marker}\nStopped JVM: {pid}\n'
                    if neoforge:
                        evidence += f'Production: verified\nRuntime JARs: {dependencies}\nInstance: {root}\n'
                    passed = log.parent / 'passed.txt'
                    passed.write_text(evidence)
                    return True, str(passed)
                if time.monotonic() - started > timeout:
                    raise RuntimeError(f'No completed startup within {timeout:g}s')
                time.sleep(0.5)
            raise RuntimeError(f'Production task exited before startup ({process.returncode})')
        except (OSError, RuntimeError, subprocess.SubprocessError) as error:
            print(f'FAIL: {error}; retained monitor logs: {log.parent}; runtime: {run_dir}', flush=True)
            return False, str(error)
        finally:
            if not stopped and pid is not None:
                try:
                    if os.name == 'nt':
                        subprocess.run(['taskkill' if os.name == 'nt' else 'taskkill.exe', '/PID', str(pid), '/T', '/F'],
                                       capture_output=True, timeout=15)
                    else:
                        os.kill(pid, signal.SIGTERM)
                except (OSError, subprocess.SubprocessError):
                    pass
            if process.poll() is None:
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    process.wait(timeout=20)
