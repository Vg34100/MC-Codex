#!/usr/bin/env python3
"""Read-only readiness checks and conservative setup; no builds or game launches."""
import json
import os
from pathlib import Path
import platform
import re
import runpy
import shutil
import subprocess
import tarfile
import sys
import tempfile
from zipfile import BadZipFile


LOCAL_DIRS = (".gradle", "build", "runs")
IGNORED_DIRS = LOCAL_DIRS + ("run", "versions", "__pycache__")
TOKENS = ("MODRINTH_TOKEN", "CURSEFORGE_TOKEN")


class Report:
    def __init__(self):
        self.rows = []
        self.development = True
        self.release = True
        self.credentials = False
        self.remote = False

    def add(self, group, status, text, scope=None):
        self.rows.append((group, status, text))
        if scope == "development" and status == "FAIL":
            self.development = False
            self.release = False
        if scope == "release" and status != "PASS":
            self.release = False

    def show(self):
        previous = None
        for group, status, text in self.rows:
            if group != previous:
                print(group)
                previous = group
            print(f"  {status} {text}")
        print("\nDevelopment: " + ("READY" if self.development else "NOT READY"))
        print("Local release preflight: " + ("READY" if self.release else "NOT READY"))
        publishing = "UNCONFIRMED (push authorization not tested)" if self.release and self.credentials and self.remote else "NOT READY"
        print("Real publishing: " + publishing)
        print("RESULT: " + ("READY" if self.development else "NOT READY"))
        return 0 if self.development else 2


def git(root, *args):
    return subprocess.run(["git", *args], cwd=root, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=15)


def ignored(root, path):
    return git(root, "check-ignore", "--no-index", "-q", "--", path).returncode == 0


def matrix_problems(root, api):
    files = sorted((root / "gradle/matrix").glob("*.properties"))
    if not files:
        return ["no gradle/matrix/*.properties targets found"]
    problems = []
    for path in files:
        seen = set()
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith(("#", "!")):
                continue
            match = re.fullmatch(r"([^=:\s]+)\s*[=:]\s*(.+)", line)
            if not match or match[1] in seen:
                problems.append(f"{path.name}: malformed/duplicate property")
                break
            seen.add(match[1])
        props = api["read_properties"](path)
        required = ("minecraft_version", "loader", "java_version", "loom_generation")
        if any(not props.get(key) for key in required):
            problems.append(f"{path.name}: missing target/version/Java/generation property")
        elif (props["loader"] not in ("fabric", "forge", "neoforge")
              or path.stem != f"{props['minecraft_version']}-{props['loader']}"
              or not re.fullmatch(r"\d[\w.+-]*", props["minecraft_version"])
              or not props["java_version"].isdigit() or int(props["java_version"]) < 8
              or props["loom_generation"] not in ("legacy", "current")):
            problems.append(f"{path.name}: inconsistent filename/loader/version/Java/generation")
    return problems


def machine_paths(root):
    # Only active tooling/configuration; no source tree, local overrides, secrets,
    # generated output, test fixtures or historical documentation.
    paths = [root / name for name in ("build-smart.py", "build.gradle", "settings.gradle",
                                     "stonecutter.gradle", "build.matrix.gradle", "gradle.properties")]
    for pattern in ("gradle/*.gradle", "gradle/*.properties", "gradle/matrix/*.properties",
                    ".github/workflows/*.yml", ".github/workflows/*.yaml", "scripts/*.py"):
        paths.extend(root.glob(pattern))
    personal = re.compile(r"(?:[A-Za-z]:[\\/]+(?:Users|Projects)[\\/]+[\w -]+[\\/]"
                          r"|/mnt/[a-z]/(?:projects|Users)/[\w -]+/|/home/[\w-]+/"
                          r"|/tmp/[\w-]*jdk\d+\b)", re.IGNORECASE)
    found = []
    for path in sorted(set(paths)):
        if not path.is_file() or path.name == "gradle.local.properties" or path.name.startswith("test-"):
            continue
        if personal.search(path.read_text(encoding="utf-8", errors="replace")):
            found.append(str(path.relative_to(root)))
    return found


def repository_checks(root, api, report):
    group = "Repository"
    valid_git = False
    if shutil.which("git"):
        try:
            worktree = git(root, "rev-parse", "--show-toplevel")
            valid_git = worktree.returncode == 0 and Path(worktree.stdout.strip()).resolve() == root.resolve()
        except (OSError, subprocess.SubprocessError):
            pass
    report.add(group, "PASS" if valid_git else "FAIL", "repository root / Git worktree" if valid_git else
               "repository root / Git worktree missing; run inside a Codex Git clone", "development")
    wrapper = ("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")
    missing = [name for name in wrapper if not (root / name).is_file()]
    report.add(group, "FAIL" if missing else "PASS", "Gradle wrapper" + (": missing " + ", ".join(missing) if missing else ""), "development")
    expected = ("settings.gradle", "stonecutter.gradle", "build.matrix.gradle", "gradle.properties",
                "gradle/resource-compat.gradle", "gradle/development.gradle",
                "scripts/smoke-release-client.py", "scripts/verify-matrix-artifacts.py")
    missing = [name for name in expected if not (root / name).is_file()]
    report.add(group, "FAIL" if missing else "PASS", "build scripts" + (": missing " + ", ".join(missing) if missing else ""), "development")
    try:
        problems = matrix_problems(root, api)
    except (OSError, UnicodeError):
        problems = ["matrix properties unreadable"]
    count = len(list((root / "gradle/matrix").glob("*.properties")))
    report.add(group, "FAIL" if problems else "PASS", "matrix configuration: " + ("; ".join(problems[:2]) if problems else f"{count} targets"), "development")
    secrets_safe = False
    if valid_git:
        tracked = git(root, "ls-files", "--", ".env", ".env.*")
        secret_files = [name for name in tracked.stdout.splitlines() if name != ".env.example"]
        secrets_safe = tracked.returncode == 0 and not secret_files
        report.add(group, "PASS" if secrets_safe else "FAIL", "no tracked .env secrets" if secrets_safe else
                   "STOP: tracked secret file; untrack it and rotate exposed credentials before continuing", "development")
        env_ignored = ignored(root, ".env")
        report.add(group, "PASS" if env_ignored else "FAIL", ".env ignored" if env_ignored else
                   ".env must be gitignored before setup", "development")
        not_ignored = [name for name in IGNORED_DIRS if not ignored(root, name + "/doctor-probe")]
        report.add(group, "FAIL" if not_ignored else "PASS", "generated directories ignored" +
                   (": add ignore rules for " + ", ".join(not_ignored) if not_ignored else ""), "development")
    found = machine_paths(root)
    report.add(group, "FAIL" if found else "PASS", "active configuration is machine-independent" if not found else
               "personal absolute path in active configuration: " + ", ".join(found), "development")
    return report.development and valid_git and secrets_safe and not problems


def probe_jdk(home):
    """Prove native java AND javac run, without trusting the directory name alone."""
    java = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")
    javac = java.with_name("javac.exe" if os.name == "nt" else "javac")
    try:
        runtime = subprocess.run([str(java), "-version"], capture_output=True, text=True, timeout=10)
        compiler = subprocess.run([str(javac), "-version"], capture_output=True, text=True, timeout=10)
        match = re.search(r'version "(\d+)(?:\.(\d+))?', runtime.stdout + runtime.stderr)
        major = int(match[2]) if match and match[1] == "1" else int(match[1]) if match else None
        if runtime.returncode or compiler.returncode or not re.search(r"\bjavac\b", compiler.stdout + compiler.stderr):
            return None
        return major
    except (OSError, subprocess.SubprocessError):
        return None


def java_checks(project, api, report):
    required = sorted({t.java for t in project.targets if t.java})
    minimum = max(required, default=0)
    selected, _, fatal = api["select_gradle_java"](project, [])
    configured, _ = api["configured_java_home"](project.root)
    on_path = shutil.which("java")
    launcher = os.environ.get("JAVA_HOME") or (Path(on_path).resolve().parent.parent if on_path else None)
    launcher_ok = bool(launcher and probe_jdk(launcher))
    report.add("Java", "PASS" if launcher_ok else "FAIL", "Gradle wrapper launcher" if launcher_ok else
               "Gradle wrapper cannot start; put a native JDK bin on PATH or set native JAVA_HOME", "development")
    home = selected or configured or launcher
    major = probe_jdk(home) if home and not fatal else None
    ok = bool(major and major >= minimum)
    report.add("Java", "PASS" if ok else "FAIL", f"Gradle JVM: Java {major} ({home})" if ok else
               f"Java {minimum}+ missing/unusable for Gradle; install a native JDK and set BUILD_SMART_JAVA_HOME to its home (bootstrap cannot install Java)", "development")
    candidates = api["candidate_jdks"](project.root)
    props = gradle_properties(project.root, api)
    if props.get("org.gradle.java.installations.auto-detect", "true").lower() == "false":
        explicit = {Path(p.strip()).resolve() for p in props.get("org.gradle.java.installations.paths", "").split(",") if p.strip()}
        explicit.update(Path(os.environ[k.strip()]).resolve() for k in props.get("org.gradle.java.installations.fromEnv", "").split(",") if os.environ.get(k.strip()))
        if home:
            explicit.add(Path(home).resolve())
        candidates = [(v, p) for v, p in candidates if p in explicit]
    for version in required:
        found = next((p for v, p in candidates if v == version and probe_jdk(p) == version), None)
        report.add("Java", "PASS" if found else "FAIL", f"target toolchain Java {version}: {found}" if found else
                   f"Java {version} target JDK missing/unusable; install that native JDK and set org.gradle.java.installations.paths in your Gradle user-home gradle.properties (bootstrap cannot install Java)", "development")


def gradle_properties(root, api):
    user = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    return {**api["read_properties"](root / "gradle.properties"), **api["read_properties"](user / "gradle.properties")}


def native_temp_probe(api):
    directory = "/tmp" if api["is_wsl"]() else None
    with tempfile.TemporaryDirectory(prefix="codex-doctor-", dir=directory) as temporary:
        probe = Path(temporary) / "probe"
        probe.write_bytes(b"probe")
        if probe.read_bytes() != b"probe":
            raise OSError("temporary storage not writable")


def portablemc_state(smoke, api):
    if os.name != "nt" and platform.system() != "Linux":
        return "unavailable", None
    if platform.machine().lower() not in ("amd64", "x86_64"):
        return "unavailable", None
    try:
        _, _, executable = smoke["neoforge_paths"]("doctor", "probe")
    except (OSError, RuntimeError, ValueError, KeyError, subprocess.SubprocessError):
        return "unavailable", None
    checksum = "PORTABLEMC_SHA256" if os.name == "nt" else "LINUX_PORTABLEMC_SHA256"
    if not re.fullmatch(r"[0-9a-f]{64}", smoke[checksum]) or not smoke["PORTABLEMC_VERSION"]:
        return "invalid", executable
    archive = "portablemc.zip" if os.name == "nt" else "portablemc.tar.gz"
    if not executable.is_file() or not executable.with_name(archive).is_file():
        return "missing", executable
    try:
        smoke["verify_portablemc"](executable)
        return "valid", executable
    except (OSError, RuntimeError, ValueError, KeyError, BadZipFile, tarfile.TarError, subprocess.SubprocessError):
        return "invalid", executable


def mcp_configured(root):
    codex = Path(os.environ.get("CODEX_HOME", Path.home() / ".codex"))
    for path in (root / ".mcp.json", root / ".codex/config.toml", codex / "config.toml"):
        try:
            if path.suffix == ".json":
                servers = json.loads(path.read_text(encoding="utf-8")).get("mcpServers", {})
                if any(key in servers and (servers[key].get("command") or servers[key].get("url")) and not servers[key].get("disabled", False) for key in ("minecraft-dev", "minecraft_dev")):
                    return True
            else:
                text = path.read_text(encoding="utf-8")
                section = re.search(r"(?m)^\s*\[mcp_servers\.(?:[\"']?minecraft[-_]dev[\"']?)\]\s*\n([^[]*)", text)
                if section and re.search(r"(?m)^\s*(command|url)\s*=", section[1]) and not re.search(r"(?m)^\s*enabled\s*=\s*false", section[1]):
                    return True
        except (OSError, ValueError, AttributeError):
            pass
    return False


def credential_status(root):
    # Secrets only enter a short-lived child's state, through the established
    # loader. Only booleans leave it; no dotenv lines/errors ever reach output.
    code = ("import json,runpy,sys; from pathlib import Path; "
            "w=runpy.run_path(sys.argv[1]); e=w['publishing_environment'](Path(sys.argv[2])); "
            "print(json.dumps({k:bool(e.get(k)) for k in ('MODRINTH_TOKEN','CURSEFORGE_TOKEN')}))")
    child = subprocess.run([sys.executable, "-c", code, str(root / "build-smart.py"), str(root)],
                           capture_output=True, text=True, timeout=15)
    if child.returncode:
        raise RuntimeError("credential status could not be confirmed (values never shown)")
    return json.loads(child.stdout)


def doctor(root, api):
    report = Report()
    safe = repository_checks(root, api, report)
    report.add("Python / Git", "PASS" if sys.version_info >= (3, 9) else "FAIL", f"Python {sys.version_info.major}.{sys.version_info.minor}" +
               (" (requires Python 3.9+)" if sys.version_info < (3, 9) else ""), "development")
    report.add("Python / Git", "PASS" if shutil.which("git") else "FAIL", "Git" if shutil.which("git") else "Git missing; install Git for this OS and add it to PATH", "development")
    if safe:
        java_checks(api["Project"](root), api, report)
    else:
        report.add("Java", "WARN", "Java checks skipped until repository/matrix security checks pass")
    wsl = api["is_wsl"]()
    report.add("Interop / runtime", "PASS" if wsl else "WARN", "WSL detected" if wsl else "not WSL; ordinary native development is supported")
    try:
        native_temp_probe(api)
        report.add("Interop / runtime", "PASS", "native temporary storage writable (probe removed)", "release")
    except OSError:
        report.add("Interop / runtime", "WARN", "native temporary storage unavailable; provide a writable native temp directory", "release")
    smoke = runpy.run_path(str(root / "scripts/smoke-release-client.py")) if (root / "scripts/smoke-release-client.py").is_file() else {}
    state, executable = portablemc_state(smoke, api) if smoke else ("unavailable", None)
    report.add("Interop / runtime", "PASS" if executable else "WARN", "Windows interop / dynamic temp and local app data resolved" if executable else
               "Windows backend unavailable in this process; check WSL interop permissions and powershell.exe/wslpath on PATH", "release")
    graphics = os.name == "nt" or bool(os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY"))
    report.add("Interop / runtime", "PASS" if graphics else "WARN", "graphical session configured (not launched)" if graphics else
               "no graphical session configured; GUI smoke requires a working display/WSLg", "release")
    build = (root / "build.matrix.gradle").read_text(encoding="utf-8") if (root / "build.matrix.gradle").is_file() else ""
    fabric = "ClientProductionRunTask" in build and "runReleaseClient" in build
    report.add("Production smoke", "PASS" if fabric else "WARN", "Fabric Loom production backend configured" if fabric else "Fabric production task missing", "release")
    messages = {"valid": "cached and checksum/version verified", "missing": "not cached; bootstrap can fetch the pinned official Windows binary (network not tested)",
                "invalid": "pin/cache invalid; repair the existing cache before bootstrap", "unavailable": "requires Windows x64 or WSL Windows interop"}
    report.add("Production smoke", "PASS" if state == "valid" else "WARN", f"NeoForge PortableMC {smoke.get('PORTABLEMC_VERSION', '?')}: {messages[state]}", "release")
    for tool in ("node", "npm"):
        report.add("Optional tooling", "PASS" if shutil.which(tool) else "WARN", tool + (" available" if shutil.which(tool) else " missing (optional; install manually if needed)"))
    mcp = mcp_configured(root)
    report.add("Optional tooling", "PASS" if mcp else "WARN", "minecraft-dev MCP configuration found (connection not tested)" if mcp else "minecraft-dev MCP could not be confirmed (optional)")
    report.add("Optional integrations", "PASS", "Mod Menu is optional; matrix Fabric development runtimes include it")
    try:
        status = credential_status(root) if safe else {}
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError):
        status = {}
    for key in TOKENS:
        state_label = "configured" if status.get(key) else "missing" if key in status else "unconfirmed"
        report.add("Publishing", "PASS" if status.get(key) else "WARN", key + " " + state_label)
    report.credentials = all(status.get(key) for key in TOKENS)
    remote = git(root, "config", "--get", "remote.origin.url") if safe else None
    report.remote = bool(remote and remote.returncode == 0 and remote.stdout.strip())
    report.add("Publishing", "WARN", "Git origin configured; push authorization not tested" if report.remote else "Git origin missing/unconfirmed (not needed for development)")
    return report


def bootstrap(root, api):
    safety = Report()
    repository_checks(root, api, safety)
    if sys.version_info < (3, 9):
        safety.add("Python / Git", "FAIL", "Python 3.9+ required; install it manually before bootstrap", "development")
    if not safety.development:
        print("Bootstrap refused: fix repository/security prerequisites before creating files.")
        return safety.show()
    prerequisites = Report()
    java_checks(api["Project"](root), api, prerequisites)
    if not prerequisites.development:
        print("Bootstrap cannot install the required JDKs; manual Java setup is required first.\nRunning doctor...")
        return doctor(root, api).show()
    print("Bootstrap")
    changed = False
    env = root / ".env"
    if env.exists() or env.is_symlink():
        print("  PASS existing .env preserved (never rewritten)")
    else:
        example = root / ".env.example"
        if not example.is_file():
            print("  FAIL .env.example missing; restore the committed empty credential template")
            return 2
        template = example.read_text(encoding="utf-8")
        if any(line.strip() and not line.lstrip().startswith("#") and (line.partition("=")[0].strip() not in TOKENS or line.partition("=")[2].strip()) for line in template.splitlines()):
            print("  FAIL .env.example must contain only empty canonical token entries (values not shown)")
            return 2
        try:
            descriptor = os.open(env, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "w", encoding="utf-8") as output:
                output.write(template)
            changed = True
            print("  CREATED ignored .env template; populate credentials manually only for publishing")
        except FileExistsError:
            print("  PASS .env appeared concurrently; preserved")
    for name in LOCAL_DIRS:
        path = root / name
        if not path.is_dir():
            path.mkdir(exist_ok=True)
            changed = True
    print("  PASS ignored local directories ready")
    smoke = runpy.run_path(str(root / "scripts/smoke-release-client.py"))
    state, executable = portablemc_state(smoke, api)
    failed = False
    if state == "valid":
        print(f"  PASS PortableMC {smoke['PORTABLEMC_VERSION']} already verified; unchanged")
    elif state == "missing":
        try:
            smoke["acquire_portablemc"](executable)
            changed = True
        except (OSError, RuntimeError, ValueError, subprocess.SubprocessError):
            print("  FAIL PortableMC acquisition/verification failed; check network/cache access and rerun bootstrap")
            failed = True
    else:
        print("  WARN PortableMC setup skipped; Windows x64 interop or a valid existing cache is required")
    if not changed and not failed:
        print("  PASS no changes required")
    print("\nRunning doctor...")
    return doctor(root, api).show() or (2 if failed else 0)


def run(root, command, api):
    try:
        return doctor(root, api).show() if command == "doctor" else bootstrap(root, api)
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError):
        print("FAIL setup prerequisite probe failed; check Git/native tool access and configuration. No secret values shown.")
        return 2
