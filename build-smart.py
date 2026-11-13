#!/usr/bin/env python3
"""
Smart Gradle Build Wrapper for Minecraft Mods
Reduces verbose output to only essential error information.
Works with any Architectury/Fabric/NeoForge mod project (single-target or Stonecutter matrix).

Usage:
    python build-smart.py doctor                  # read-only prerequisite/readiness checks
    python build-smart.py bootstrap               # safe setup, then doctor; never installs JDKs
    python build-smart.py                         # compile only (fast check)
    python build-smart.py compile                 # compile every target / loader
    python build-smart.py compile:<selector>      # compile selected targets (see selectors)
    python build-smart.py build                   # package release jars
    python build-smart.py shadowJar               # package release jars
    python build-smart.py clean                   # clean build dirs
    python build-smart.py smoke-server:<target>   # start a dedicated server, wait for Done, stop it
    python build-smart.py smoke-release-client:<target>
                                                  # package, verify, launch the actual release jar
    python build-smart.py release-smoke            # four representative production clients, sequentially
    python build-smart.py publish:plan             # read-only target/artifact publication plan
    python build-smart.py publish:modrinth-dry-run  # Minotaur debug mode; never uploads
    python build-smart.py publish:curseforge-dry-run # CurseForgeGradle debug mode; never uploads
    python build-smart.py publish:all-dry-run      # both platforms, all matrix nodes
    python build-smart.py publish:preflight        # package, verify, release-smoke, both dry-runs
    python build-smart.py publish:modrinth --confirm # REAL uploads; explicit confirmation required
    python build-smart.py publish:curseforge --confirm # REAL uploads; explicit confirmation required
    python build-smart.py publish:all --confirm    # REAL sequential uploads, resumable API receipts
    python build-smart.py matrix                  # compile + package
    python build-smart.py matrix:compile          # compile all targets
    python build-smart.py matrix:package          # package all targets
    python build-smart.py matrix:server           # generate server launch setup only (no server)
    python build-smart.py matrix:servers-runtime[:<selector>]
                                                  # smoke-test dedicated servers sequentially
    python build-smart.py [any-task] [args...]    # run any custom gradle task

Selectors: a loader (fabric, neoforge, ...), "modern", "legacy", or an exact target name.
Stonecutter targets come from gradle/matrix/*.properties (falling back to settings.gradle or
versions/). A target is "legacy" when its properties set loom_generation=legacy, or, if no target
declares loom_generation, when its java_version is below the highest declared one.

Wrapper-only flag:
    --print-plan       show the Gradle JVM and tasks that would run, without running Gradle

Environment:
    BUILD_SMART_JAVA_HOME              force the JVM that runs Gradle
    BUILD_SMART_SERVER_READY_TIMEOUT   seconds to wait for "Done (...)!" (default 1200)
    BUILD_SMART_SERVER_STOP_TIMEOUT    seconds to wait for exit after "stop" (default 180)
    BUILD_SMART_RELEASE_READY_TIMEOUT  client startup timeout (default 180)
    BUILD_SMART_RELEASE_INSTALL_TIMEOUT PortableMC first-install timeout (default 600)
"""

import os
import platform
import queue
import re
import runpy
import shutil
import signal
import subprocess
import sys
import threading
import tempfile
import time
import uuid
from pathlib import Path

# Lines to always filter out (noise)
NOISE_PATTERNS = [
    "Incubating",
    "Problems report is available",
    "Deprecated Gradle features",
    "warning-mode all",
    "For more on this",
    "docs.gradle.org",
    "Consider enabling configuration cache",
    "BUILD SUCCESSFUL",
    "actionable task",
    "up-to-date",
    "Architectury Loom:",
    "Architect Plugin:",
    "Please report any issues",
    "> Configure project",
    "> Task :",
    "Note: Some input files",
    "Recompile with -Xlint",
    "warnings",
]

# Patterns that indicate actual errors we want to capture
ERROR_INDICATORS = [
    "error:",
    "Error:",
    "FAILED",
    "Exception:",
    "cannot find symbol",
    "incompatible types",
    "method does not override",
    "unreported exception",
    "package .* does not exist",
    "class .* is public, should be declared",
    "Could not .*",
    "A problem occurred .*",
    "Plugin .* was not found",
    "No signature of method",
    "non-static .* cannot be referenced",
    "constructor .* cannot be applied",
    "Caused by:",
    "Legacy resource transform failed",
]

# Some locales print the startup time with a decimal comma.
SERVER_READY = re.compile(r'\bDone \(\d+(?:[.,]\d+)?s\)!')
SERVER_STOPPING = re.compile(r'\bStopping server\b')
EULA_PROMPT = re.compile(r'agree to the EULA', re.IGNORECASE)

RUNTIME_INDICATORS = [SERVER_READY, SERVER_STOPPING, EULA_PROMPT] + [
    re.compile(pattern, re.IGNORECASE) for pattern in (
        r'OpenAL initialized',
        r'Created: \d+x\d+x\d+ .*atlas',
        r'All dimensions are saved',
        r'blocks? filled',
        r'No blocks were filled',
        r'\[(?:main|Render thread|Server thread)/ERROR\]',
        r'Exception in thread',
        r'Caused by:',
    )
]

# Vanilla first-run / offline-mode noise that is logged at ERROR but is harmless.
BENIGN_RUNTIME_ERRORS = (
    "Failed to load properties from file: server.properties",
    "Yggdrasil Key Fetcher/ERROR",
)

ANSI_ESCAPE = re.compile(r'\x1b\[[0-9;?]*[ -/]*[@-~]')
KNOWN_LOADERS = ("fabric", "neoforge", "forge", "quilt")
WRAPPER_FLAGS = ("--print-plan",)


def find_project_root():
    """Find project root by looking for gradlew or settings.gradle"""
    current = Path.cwd().resolve()
    while True:
        if any((current / name).exists() for name in
               ("gradlew", "gradlew.bat", "settings.gradle", "settings.gradle.kts")):
            return current
        if current == current.parent:
            break
        current = current.parent
    # Also support invoking this repository's wrapper by absolute path from
    # outside the worktree, without guessing the caller's unrelated directory.
    script_root = Path(__file__).resolve().parent
    if (script_root / "gradlew").is_file() or (script_root / "gradlew.bat").is_file():
        return script_root
    return Path.cwd().resolve()


def is_noise(line):
    """Check if line is gradle noise we should filter"""
    return any(pattern in line for pattern in NOISE_PATTERNS)


def is_error_line(line):
    """Check if line contains an error indicator"""
    return any(re.search(pattern, line, re.IGNORECASE) for pattern in ERROR_INDICATORS)


def read_properties(path):
    """Minimal java.util.Properties reader (no line continuations)."""
    props = {}
    try:
        text = Path(path).read_text(encoding="utf-8", errors="replace")
    except OSError:
        return props
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line[0] in "#!":
            continue
        match = re.match(r'((?:\\.|[^=:\s])+)\s*[=:\s]\s*(.*)', line)
        if not match:
            props[line] = ""
            continue
        key, value = match.groups()
        props[key] = re.sub(r'\\(.)', r'\1', value.strip())
    return props


def version_key(text):
    """Sort key that orders 1.21 < 1.21.1 < 26.1 numerically."""
    return [(0, int(part), "") if part.isdigit() else (1, 0, part)
            for part in re.split(r'[.\-+]', text)]


# ---------------------------------------------------------------------------
# Project layout discovery
# ---------------------------------------------------------------------------

class Target:
    def __init__(self, name, props):
        self.name = name
        self.props = props
        self.loader = props.get("loader") or name.rsplit("-", 1)[-1]
        self.version = props.get("minecraft_version") or name.rsplit("-", 1)[0]
        java = props.get("java_version", "")
        self.java = int(java) if java.isdigit() else None
        self.legacy = False


class Project:
    def __init__(self, root):
        self.root = root
        self.stonecutter = any((root / name).exists() for name in
                               ("stonecutter.gradle", "stonecutter.gradle.kts"))
        self.targets = self._discover_targets() if self.stonecutter else []
        self.platforms = [] if self.stonecutter else self._discover_platforms()
        self.has_common = (root / "common").is_dir()
        self._root_scripts = None

    # Stonecutter -----------------------------------------------------------

    def _discover_targets(self):
        targets = {}
        for props_file in sorted((self.root / "gradle" / "matrix").glob("*.properties")):
            targets[props_file.stem] = Target(props_file.stem, read_properties(props_file))

        if not targets:
            for name in ("settings.gradle", "settings.gradle.kts"):
                path = self.root / name
                if path.exists():
                    text = path.read_text(encoding="utf-8", errors="replace")
                    for match in re.finditer(r'\b(?:version|vers)\s*\(\s*["\']([^"\']+)["\']', text):
                        targets.setdefault(match.group(1), Target(match.group(1), {}))

        if not targets and (self.root / "versions").is_dir():
            for child in sorted((self.root / "versions").iterdir()):
                if child.is_dir():
                    targets[child.name] = Target(child.name, {})

        ordered = sorted(targets.values(), key=lambda t: (version_key(t.version), t.loader))
        if any("loom_generation" in t.props for t in ordered):
            for t in ordered:
                t.legacy = t.props.get("loom_generation", "").lower() == "legacy"
        else:
            known = [t.java for t in ordered if t.java]
            newest = max(known) if known else None
            for t in ordered:
                t.legacy = bool(newest and t.java and t.java < newest)
        return ordered

    def has_root_task(self, name):
        """True if the root build scripts register an aggregate task with this name."""
        if self._root_scripts is None:
            parts = []
            for script in ("stonecutter.gradle", "stonecutter.gradle.kts",
                           "build.gradle", "build.gradle.kts"):
                path = self.root / script
                if path.exists():
                    parts.append(path.read_text(encoding="utf-8", errors="replace"))
            self._root_scripts = "\n".join(parts)
        return re.search(r'["\']%s["\']' % re.escape(name), self._root_scripts) is not None

    def select_targets(self, selector):
        if not selector or selector == "all":
            return list(self.targets)
        if selector == "modern":
            return [t for t in self.targets if not t.legacy]
        if selector == "legacy":
            return [t for t in self.targets if t.legacy]
        exact = [t for t in self.targets if t.name == selector]
        if exact:
            return exact
        return [t for t in self.targets if selector in (t.loader, t.version)]

    def matrix_or_each(self, aggregate, per_target):
        if self.has_root_task(aggregate):
            return [aggregate]
        return [f":{t.name}:{per_target}" for t in self.targets]

    # Single-target Architectury -------------------------------------------

    def _discover_platforms(self):
        def is_project(name):
            return any((self.root / name / f).exists() for f in ("build.gradle", "build.gradle.kts"))

        enabled = read_properties(self.root / "gradle.properties").get("enabled_platforms", "")
        names = [p.strip() for p in enabled.split(",") if p.strip()]
        found = [p for p in names if is_project(p)]
        return found or [p for p in KNOWN_LOADERS if is_project(p)]

    def platform_compile(self, platforms):
        common = [":common:compileJava"] if self.has_common else []
        return common + [f":{p}:compileJava" for p in platforms]


# ---------------------------------------------------------------------------
# Gradle JVM selection
# ---------------------------------------------------------------------------

def is_wsl():
    return os.name != "nt" and "microsoft" in platform.release().lower()


def java_binary(home):
    return Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")


def jdk_major(home):
    """Major version from a JDK's release file, or None."""
    release = Path(home) / "release"
    try:
        text = release.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    match = re.search(r'^JAVA_VERSION="?([0-9._]+)', text, re.MULTILINE)
    if not match:
        return None
    parts = match.group(1).split(".")
    return int(parts[1]) if parts[0] == "1" and len(parts) > 1 else int(parts[0])


def major_from_name(path_text):
    """Best-effort major version from a JDK directory name such as ms-25.0.3 or jdk1.8.0_202."""
    name = re.split(r'[\\/]', path_text.rstrip("\\/"))[-1]
    for number in re.findall(r'\d+', name):
        if 8 <= int(number) <= 99:
            return int(number)
    return None


def windows_to_wsl(path_text):
    match = re.match(r'^([A-Za-z]):[\\/](.*)$', path_text)
    if not match:
        return None
    return Path("/mnt") / match.group(1).lower() / match.group(2).replace("\\", "/")


def candidate_jdks(root=None):
    """Locally installed JDK homes runnable on this OS."""
    home = Path.home()
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", home / ".gradle"))
    roots = [home / ".jdks", gradle_home / "jdks", home / ".sdkman" / "candidates" / "java"]
    if os.name == "nt":
        for env in ("ProgramFiles", "ProgramW6432"):
            base = os.environ.get(env)
            if base:
                roots += [Path(base) / vendor for vendor in
                          ("Java", "Eclipse Adoptium", "Microsoft", "Zulu", "Amazon Corretto",
                           "BellSoft", "Semeru")]
    else:
        roots += [Path("/usr/lib/jvm"), Path("/usr/java"), Path("/opt/java"), Path("/opt"),
                  Path("/Library/Java/JavaVirtualMachines")]

    homes = []
    for key in ("JAVA_HOME", "BUILD_SMART_JAVA_HOME"):
        if os.environ.get(key):
            homes.append(Path(os.environ[key]))
    on_path = shutil.which("java")
    if on_path:
        homes.append(Path(on_path).resolve().parent.parent)
    # Reuse Gradle's explicit toolchain settings, including nonstandard JDK locations.
    props = read_properties(gradle_home / "gradle.properties")
    if root is not None:
        props = {**read_properties(root / "gradle.properties"), **props}
    homes += [Path(p.strip()) for p in props.get("org.gradle.java.installations.paths", "").split(",") if p.strip()]
    for key in props.get("org.gradle.java.installations.fromEnv", "").split(","):
        if os.environ.get(key.strip()):
            homes.append(Path(os.environ[key.strip()]))
    for root in roots:
        try:
            children = list(root.iterdir())
        except OSError:
            continue
        for child in children:
            homes.append(child / "Contents" / "Home" if (child / "Contents" / "Home").is_dir() else child)

    seen, result = set(), []
    for candidate in homes:
        try:
            real = candidate.resolve()
        except OSError:
            continue
        if real in seen or not java_binary(real).exists():
            continue
        seen.add(real)
        major = jdk_major(real) or major_from_name(str(real))
        if major:
            result.append((major, real))
    return result


def configured_java_home(root):
    """org.gradle.java.home as Gradle would see it (user home overrides project)."""
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    for props_file in (gradle_home / "gradle.properties", root / "gradle.properties"):
        value = read_properties(props_file).get("org.gradle.java.home")
        if value:
            return value, props_file
    return None, None


def select_gradle_java(project, args):
    """
    Return (java_home_override_or_None, message_or_None, fatal).

    Never forces a JVM when the configured one is usable: per-target Java versions are the
    build's job (toolchains / javaLauncher), not the wrapper's.
    """
    if any("org.gradle.java.home" in arg for arg in args):
        return None, None, False

    forced = os.environ.get("BUILD_SMART_JAVA_HOME")
    if forced:
        if not java_binary(forced).exists():
            return None, f"BUILD_SMART_JAVA_HOME={forced} has no {java_binary(forced).name}", True
        return forced, f"Gradle JVM: {forced} (BUILD_SMART_JAVA_HOME)", False

    configured, source = configured_java_home(project.root)
    if configured and java_binary(configured).exists():
        return None, None, False

    if not configured:
        # A fresh shell may expose only legacy Java. Do not silently run current
        # Loom on it; use the same installed-JDK discovery as the setup checks.
        required = max((t.java for t in project.targets if t.java), default=0)
        on_path = shutil.which("java")
        launch_home = os.environ.get("JAVA_HOME") or (str(Path(on_path).resolve().parent.parent) if on_path else None)
        if launch_home and java_binary(launch_home).exists() and (jdk_major(launch_home) or 0) >= required:
            return None, None, False
        candidates = sorted((major, home) for major, home in candidate_jdks(project.root) if major >= required)
        if candidates:
            chosen = candidates[0][1]
            return str(chosen), f"Gradle JVM: {chosen} (matrix requires Java {required}+)", False
        return None, (f"Java {required}+ JDK missing for Gradle/current targets; install that JDK and set "
                      "BUILD_SMART_JAVA_HOME to its native JDK home. Bootstrap does not install Java."), True

    # Configured JVM is unusable here (typically a Windows path seen from WSL/Linux).
    required = None
    translated = windows_to_wsl(configured) if is_wsl() else None
    if translated is not None:
        required = jdk_major(translated)
    required = required or major_from_name(configured)
    if required is None and project.targets:
        known = [t.java for t in project.targets if t.java]
        required = max(known) if known else None

    candidates = candidate_jdks(project.root)
    if required is not None:
        exact = sorted(home for major, home in candidates if major == required)
        newer = sorted((major, home) for major, home in candidates if major > required)
        chosen = exact[0] if exact else (newer[0][1] if newer else None)
    else:
        chosen = max(candidates)[1] if candidates else None

    if chosen is None:
        wanted = f"a Java {required}+ JDK" if required else "a JDK"
        hint = ("run through cmd.exe on Windows, " if is_wsl() else "") + \
               f"install {wanted}, or set BUILD_SMART_JAVA_HOME"
        return None, (f"org.gradle.java.home={configured} (from {source}) is not usable on this OS "
                      f"and no local replacement was found; {hint}."), True

    detail = f"needs Java {required}+" if required else "newest local JDK"
    return str(chosen), (f"Gradle JVM: {chosen} ({detail}; configured "
                         f"{configured} is not runnable on this OS)"), False


# ---------------------------------------------------------------------------
# Gradle execution
# ---------------------------------------------------------------------------

def gradle_command(project, tasks, java_home):
    if os.name == "nt":
        cmd = [str(project.root / "gradlew.bat")]
    else:
        gradlew = project.root / "gradlew"
        cmd = ["./gradlew"] if os.access(gradlew, os.X_OK) else ["sh", "gradlew"]
    if java_home:
        cmd.append(f"-Dorg.gradle.java.home={java_home}")
    return cmd + list(tasks) + ["--no-daemon"]


def popen(cmd, cwd, stdin, isolate=False, env=None):
    kwargs = dict(cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=stdin,
                  text=True, encoding="utf-8", errors="replace", bufsize=1, env=env)
    if not isolate:
        return subprocess.Popen(cmd, **kwargs)
    # Own process group so a timeout can kill Gradle and its forked game JVM together.
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    return subprocess.Popen(cmd, **kwargs)


def kill_tree(process):
    """Kill Gradle and the forked Minecraft JVM, not just the wrapper shell."""
    if process.poll() is not None:
        return
    try:
        if os.name == "nt":
            subprocess.run(["taskkill", "/T", "/F", "/PID", str(process.pid)],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            os.killpg(process.pid, signal.SIGTERM)  # pid == pgid (start_new_session)
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
    except (OSError, ProcessLookupError):
        pass
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        pass


def stream_lines(process):
    """Yield (line_or_None) with a 1s tick so callers can enforce timeouts."""
    lines = queue.Queue()

    def pump():
        for line in process.stdout:
            lines.put(line)
        lines.put(None)

    threading.Thread(target=pump, daemon=True).start()
    while True:
        try:
            line = lines.get(timeout=1)
        except queue.Empty:
            yield None
            continue
        if line is None:
            return
        yield line


def show_runtime(plain):
    if any(pattern.search(plain) for pattern in RUNTIME_INDICATORS):
        print(plain, flush=True)


def run_gradle(project, tasks, java_home, env=None):
    """Run gradle with given tasks and capture output"""
    cmd = gradle_command(project, tasks, java_home)
    print(f"Running: {' '.join(cmd)}")
    print("-" * 60)

    stream_runtime = any(task.lower().endswith(("runclient", "runserver")) for task in tasks)
    # Interactive run tasks keep the terminal's stdin so the user can type server commands.
    process = popen(cmd, project.root, None, env=env)
    output_lines = []
    try:
        for line in process.stdout:
            stripped = line.rstrip()
            output_lines.append(stripped)
            if stream_runtime:
                show_runtime(ANSI_ESCAPE.sub('', stripped))
            if stripped.startswith(("Successfully uploaded version ", "PUBLISH RESULT ")):
                # Preserve partial-publication evidence even if a later target fails.
                print(stripped, flush=True)
        process.wait()
    except KeyboardInterrupt:
        kill_tree(process)
        raise
    return process.returncode, output_lines


def run_server_smoke(project, run_task, extra_args, java_home):
    """
    Start a dedicated server, wait for its Done milestone, send `stop`, and require a clean exit.
    Returns (ok, reason, return_code, lines).
    """
    ready_timeout = float(os.environ.get("BUILD_SMART_SERVER_READY_TIMEOUT", "1200"))
    stop_timeout = float(os.environ.get("BUILD_SMART_SERVER_STOP_TIMEOUT", "180"))

    cmd = gradle_command(project, [run_task] + list(extra_args), java_home)
    print(f"Running: {' '.join(cmd)}")
    print("-" * 60)

    process = popen(cmd, project.root, subprocess.PIPE, isolate=True)
    lines = []
    ready = stopping = eula = timed_out = False
    started = time.monotonic()
    stop_sent_at = None

    try:
        for line in stream_lines(process):
            now = time.monotonic()
            if line is not None:
                stripped = line.rstrip()
                lines.append(stripped)
                plain = ANSI_ESCAPE.sub('', stripped)
                show_runtime(plain)
                eula = eula or bool(EULA_PROMPT.search(plain))
                stopping = stopping or (ready and bool(SERVER_STOPPING.search(plain)))
                if not ready and SERVER_READY.search(plain):
                    ready = True
                    try:
                        process.stdin.write("stop\n")
                        process.stdin.flush()
                    except OSError:
                        pass
                    stop_sent_at = now
            if stop_sent_at is None and now - started > ready_timeout:
                timed_out = "ready"
                kill_tree(process)
                break
            if stop_sent_at is not None and now - stop_sent_at > stop_timeout:
                timed_out = "stop"
                kill_tree(process)
                break
        process.wait()
    except KeyboardInterrupt:
        kill_tree(process)
        raise
    finally:
        try:
            process.stdin.close()
        except OSError:
            pass

    return_code = process.returncode
    unexpected = [
        line for line in lines
        if ("/ERROR]" in line or "Exception in thread" in line)
        and not any(benign in line for benign in BENIGN_RUNTIME_ERRORS)
    ]

    if timed_out == "ready":
        reason = f"server did not reach Done within {ready_timeout:.0f}s"
    elif timed_out == "stop":
        reason = f"server did not exit within {stop_timeout:.0f}s of `stop`"
    elif not ready:
        reason = "process exited before the server reached Done"
        if eula:
            reason += " (EULA not accepted: set eula=true in the target's run/eula.txt and rerun)"
    elif return_code != 0:
        reason = f"Gradle exited with code {return_code} after `stop`"
    elif not stopping:
        reason = "server exited without logging a clean `Stopping server` shutdown"
    elif unexpected:
        reason = "unexpected runtime errors were logged"
    else:
        return True, "reached Done and stopped cleanly", return_code, lines

    print("-" * 60)
    print(f"SMOKE TEST FAILED: {reason}")
    for line in unexpected[:20]:
        print(f"    {line}")
    return False, reason, return_code, lines


def process_output(lines, return_code):
    """Process gradle output and print relevant info"""

    if return_code == 0:
        print("=" * 60)
        print("BUILD SUCCESS")
        print("=" * 60)
        return

    print("=" * 60)
    print("BUILD FAILED")
    print("=" * 60)

    errors = []
    seen_errors = set()

    i = 0
    while i < len(lines):
        line = lines[i]

        if not line.strip() or is_noise(line):
            i += 1
            continue

        if is_error_line(line):
            error_msg = line.strip()
            error_msg = re.sub(r'[A-Z]:\\[^:]+\\([^\\]+\.java)', r'\1', error_msg)
            error_msg = re.sub(r'/[^:]+/([^/]+\.java)', r'\1', error_msg)

            error_key = error_msg[:100]
            if error_key not in seen_errors:
                seen_errors.add(error_key)
                errors.append(error_msg)

                j = i + 1
                while j < len(lines) and j < i + 4:
                    follow = lines[j].strip()
                    if follow and not is_noise(follow):
                        if follow.startswith("symbol:") or follow.startswith("location:"):
                            errors.append(f"    {follow}")
                        elif "required:" in follow or "found:" in follow:
                            errors.append(f"    {follow}")
                    j += 1

        i += 1

    if errors:
        print("\nErrors found:")
        print("-" * 60)
        for err in errors[:100]:
            print(err)
        if len(errors) > 100:
            print(f"\n... and {len(errors) - 100} more errors")
    else:
        print("\nCouldn't parse specific errors. Last 30 lines:")
        print("-" * 60)
        relevant_lines = [l for l in lines if l.strip() and not is_noise(l)]
        for line in relevant_lines[-30:]:
            print(line)

    for i, line in enumerate(lines):
        if line.strip() == "* What went wrong:":
            details = [entry for entry in lines[i:i + 12] if entry.strip() and not is_noise(entry)]
            if details:
                print("\nGradle failure details:")
                print("-" * 60)
                for entry in details:
                    print(entry)
            break

    print("-" * 60)
    print("Fix errors and rebuild")


# ---------------------------------------------------------------------------
# Task selection
# ---------------------------------------------------------------------------

class PlanError(Exception):
    pass


def publishing_environment(root):
    """Credentials go only to a publishing child's environment, never CLI/logs.

    Only publication tokens are accepted; explicit environment values (even empty
    ones) win. Compile, package, plan and debug uploads never open .env.
    """
    tracked = subprocess.run(["git", "ls-files", "--", ".env"], cwd=root,
                             capture_output=True, check=True)
    if tracked.stdout:
        raise PlanError("STOP: .env is tracked. Remove it from tracking and rotate the credential before publishing.")
    env = os.environ.copy()
    path = root / ".env"
    allowed = ("MODRINTH_TOKEN", "CURSEFORGE_TOKEN")
    if any(key not in env for key in allowed) and path.is_file():
        with path.open(encoding="utf-8-sig") as entries:
            for raw in entries:
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                key, separator, value = line.partition("=")
                key = key.strip()
                if separator and key in allowed and key not in env:
                    value = value.strip()
                    if value[:1] in ("'", '"'):
                        if len(value) < 2 or value[-1] != value[0]:
                            raise PlanError(f"Invalid quoting in .env {key} entry (value not shown).")
                        value = value[1:-1]
                    env.setdefault(key, value)
    return env


def plan(project, task_arg):
    """
    Map a wrapper command to ("gradle", [tasks]) or ("servers", [run tasks]).
    Extra CLI args are appended by the caller.
    """
    stonecutter = project.stonecutter

    def targets_for(selector):
        selected = project.select_targets(selector)
        if not selected:
            names = ", ".join(t.name for t in project.targets) or "none found"
            raise PlanError(f"No Stonecutter targets match '{selector}' (targets: {names})")
        return selected

    def platforms_for(selector):
        if selector in (None, "", "all", "modern"):
            if not project.platforms:
                raise PlanError("No loader subprojects found (checked enabled_platforms and "
                                f"{', '.join(KNOWN_LOADERS)})")
            return project.platforms
        if selector == "legacy":
            raise PlanError("'legacy' only applies to Stonecutter matrix projects")
        if selector not in project.platforms:
            raise PlanError(f"No loader subproject '{selector}' (found: {', '.join(project.platforms) or 'none'})")
        return [selector]

    if task_arg.startswith("smoke-release-client:"):
        name = task_arg.split(":", 1)[1]
        selected = [t for t in project.targets if t.name == name and t.loader in ("fabric", "forge", "neoforge")]
        if not selected:
            raise PlanError("Packaged client smoke needs an exact registered Fabric/Forge/NeoForge target")
        return "release-client", selected

    if task_arg == "release-smoke":
        # Deliberate representative coverage, not the full supported matrix.
        selected = sorted(
            (t for t in project.targets if t.props.get("release_smoke") == "true"),
            key=lambda t: (t.legacy, t.loader, version_key(t.version)))
        if not selected:
            raise PlanError("No representative release_smoke=true targets in the matrix")
        return "release-clients", selected

    if task_arg.startswith("smoke-server:"):
        target = task_arg.split(":", 1)[1]
        if not target:
            raise PlanError("smoke-server needs a target, e.g. smoke-server:fabric")
        if stonecutter and target not in {t.name for t in project.targets}:
            print(f"Warning: '{target}' is not a discovered Stonecutter target", file=sys.stderr)
        return "servers", [f":{target}:runServer"]

    if task_arg == "matrix:servers-runtime" or task_arg.startswith("matrix:servers-runtime:"):
        selector = task_arg.partition("matrix:servers-runtime:")[2] or None
        if stonecutter:
            return "servers", [f":{t.name}:runServer" for t in targets_for(selector)]
        return "servers", [f":{p}:runServer" for p in platforms_for(selector)]

    if task_arg in ("compile", "matrix:compile"):
        if stonecutter:
            return "gradle", project.matrix_or_each("compileMatrix", "compileJava")
        return "gradle", project.platform_compile(platforms_for(None))

    if task_arg.startswith("compile:"):
        selector = task_arg.split(":", 1)[1]
        if stonecutter:
            return "gradle", [f":{t.name}:compileJava" for t in targets_for(selector)]
        return "gradle", project.platform_compile(platforms_for(selector))

    if task_arg in ("matrix:package", "build", "shadowJar", "release"):
        if stonecutter:
            return "gradle", project.matrix_or_each("packageMatrix", "build")
        return "gradle", ["shadowJar"]

    if task_arg == "matrix:server":
        # Generate launch configuration only; never start Minecraft here.
        if stonecutter:
            return "gradle", project.matrix_or_each("verifyServerLaunchSetup", "configureLaunch")
        return "gradle", [f":{p}:configureLaunch" for p in platforms_for(None)]

    if task_arg == "matrix":
        if stonecutter:
            if project.has_root_task("verifyMatrix"):
                return "gradle", ["verifyMatrix"]
            return "gradle", (project.matrix_or_each("compileMatrix", "compileJava")
                              + project.matrix_or_each("packageMatrix", "build")
                              + project.matrix_or_each("verifyServerLaunchSetup", "configureLaunch"))
        platforms = platforms_for(None)
        return "gradle", (project.platform_compile(platforms) + ["shadowJar"]
                          + [f":{p}:configureLaunch" for p in platforms])

    return "gradle", [task_arg]


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] in ("--help", "-h", "help"):
        print(__doc__)
        return 0
    print_plan = "--print-plan" in argv
    argv = [arg for arg in argv if arg not in WRAPPER_FLAGS]

    task_arg = argv[0] if argv else "compile"
    extra_args = argv[1:]

    root = find_project_root()
    if task_arg in ("doctor", "bootstrap"):
        if extra_args or print_plan:
            print("ERROR: doctor/bootstrap take no flags; doctor never runs Gradle or Minecraft.")
            return 2
        if not (root / "scripts/setup-environment.py").is_file():
            print("FAIL Codex setup helper missing; restore scripts/setup-environment.py in the clone.")
            return 2
        setup = runpy.run_path(str(root / "scripts/setup-environment.py"))
        return setup["run"](root, task_arg, globals())

    project = Project(root)
    if task_arg.startswith("publish:"):
        try:
            publishing = runpy.run_path(str(project.root / "scripts/publish-release.py"))
            return publishing["run"](project, task_arg, extra_args, print_plan, globals())
        except (PlanError, ValueError, AssertionError, OSError, RuntimeError) as error:
            print(f"ERROR: {error}")
            return 2
    try:
        kind, tasks = plan(project, task_arg)
    except PlanError as exc:
        print(f"ERROR: {exc}")
        return 2

    if kind == "release-clients":
        print("Representative release smokes (sequential): " + ", ".join(t.name for t in tasks))
        for target in tasks:
            code = main([f"smoke-release-client:{target.name}"] + extra_args + (["--print-plan"] if print_plan else []))
            if code:
                return code
        if not print_plan:
            print(f"RELEASE SMOKE PASS ({len(tasks)}/{len(tasks)})")
        return 0

    java_home, java_message, fatal = select_gradle_java(project, extra_args)
    if java_message:
        print(("ERROR: " if fatal else "") + java_message)

    if kind == "release-client":
        target = tasks[0]
        smoke = runpy.run_path(str(project.root / "scripts/smoke-release-client.py"))
        root_pins = read_properties(project.root / "gradle.properties")
        artifact = project.root / "build/libs" / target.name / (
            f"{root_pins['archives_name']}-{target.loader}-{target.version}-{root_pins['mod_version']}.jar")
        run_dir = Path(tempfile.gettempdir()) / "codex-release-smoke" / target.name / uuid.uuid4().hex
        log_dir = run_dir
        neoforge = None
        if target.loader in ("forge", "neoforge") or target.legacy or target.props.get("release_launcher") == "portablemc":
            try:
                run_dir, native_root, executable = smoke["neoforge_paths"](target.name, log_dir.name)
            except (OSError, RuntimeError, subprocess.SubprocessError) as error:
                print(f"ERROR: NeoForge production launcher prerequisites: {error}")
                return 2
            loader_key = {"fabric": "fabric_loader_version", "forge": "forge_version", "neoforge": "neoforge_version"}[target.loader]
            neoforge = (native_root, executable, target.props[loader_key], target.loader, target.version)
        client_tasks = [f":{target.name}:runReleaseClient", f"-Prelease_smoke_dir={run_dir}"] + extra_args
        if neoforge:
            client_tasks[0] = f":{target.name}:stageReleaseClient"

    if print_plan:
        layout = "stonecutter" if project.stonecutter else "single-target"
        print(f"Layout: {layout}")
        if project.stonecutter:
            print("Targets: " + ", ".join(
                t.name + (" (legacy)" if t.legacy else "") for t in project.targets))
        else:
            print("Platforms: " + ", ".join(project.platforms))
        if kind == "release-client":
            print(f"Release smoke: {target.name}\n  Packaged JAR: {artifact}\n  Game Java: {target.java}")
            mechanism = f"PortableMC {smoke['PORTABLEMC_VERSION']}, managed Java" if neoforge else "Loom ClientProductionRunTask"
            print(f"  Production mechanism: {mechanism}\n  Isolated directory: {run_dir}")
            print("  Package: " + " ".join(gradle_command(project, [f":{target.name}:packageTarget"] + extra_args, java_home)))
            print(("  Stage: " if neoforge else "  Launch: ") + " ".join(gradle_command(project, client_tasks, java_home)))
            if neoforge:
                print("  Launch: " + " ".join(smoke["neoforge_command"](executable, native_root, neoforge[2], target.legacy, target.loader, target.version)))
                print(f"  PortableMC cache: {executable.parent}\n  Monitor logs: {log_dir}")
        elif kind == "servers":
            for task in tasks:
                print("Smoke: " + " ".join(gradle_command(project, [task] + extra_args, java_home)))
        else:
            print("Gradle: " + " ".join(gradle_command(project, tasks + extra_args, java_home)))
        return 2 if fatal else 0

    if fatal:
        return 2

    if kind == "release-client":
        code, output = run_gradle(project, [f":{target.name}:packageTarget"] + extra_args, java_home)
        process_output(output, code)
        if code:
            return code
        verifier = runpy.run_path(str(project.root / "scripts/verify-matrix-artifacts.py"))
        try:
            artifact = verifier["inspect"](project.root / "gradle/matrix" / (target.name + ".properties"))
        except (AssertionError, ValueError, OSError) as error:
            print(f"FAIL: release artifact verification: {error}")
            return 1
        ok, reason = smoke["run"](gradle_command(project, client_tasks, java_home), run_dir, artifact, project.root,
                                  legacy=target.legacy, neoforge=neoforge, log_dir=log_dir)
        print(f"{'PASS' if ok else 'FAIL'} {target.name}: {reason}")
        return 0 if ok else 1

    if kind == "servers":
        results = []
        for task in tasks:
            ok, reason, return_code, lines = run_server_smoke(project, task, extra_args, java_home)
            # Show Gradle's own errors when it failed before startup (not when we killed it).
            if not ok and reason.startswith("process exited") and return_code not in (0, None):
                process_output(lines, return_code)
            results.append((task, ok, reason))
        print("=" * 60)
        print("SERVER SMOKE TESTS " + ("PASSED" if all(ok for _, ok, _ in results) else "FAILED"))
        print("=" * 60)
        for task, ok, reason in results:
            print(f"{'PASS' if ok else 'FAIL'}  {task}: {reason}")
        return 0 if all(ok for _, ok, _ in results) else 1

    return_code, lines = run_gradle(project, tasks + extra_args, java_home)
    process_output(lines, return_code)
    return return_code


if __name__ == "__main__":
    sys.exit(main())
