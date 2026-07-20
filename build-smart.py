#!/usr/bin/env python3
"""
Smart Gradle Build Wrapper for Minecraft Mods
Reduces verbose output to only essential error information.
Works with any Architectury/Fabric/NeoForge mod project.

Usage:
    python build-smart.py              # compile only (fast)
    python build-smart.py compile      # compile only (fast)
    python build-smart.py build        # full build with jars
    python build-smart.py shadowJar    # distribution jars
    python build-smart.py clean        # clean build dirs
    python build-smart.py [any-task]   # any gradle task
"""

import subprocess
import sys
import re
import os
from pathlib import Path

# Detect OS for gradle wrapper
GRADLEW = "gradlew.bat" if os.name == "nt" else "./gradlew"

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
    "Exception",
    "cannot find symbol",
    "incompatible types",
    "method does not override",
    "unreported exception",
    "package .* does not exist",
    "class .* is public, should be declared",
    "non-static .* cannot be referenced",
    "constructor .* cannot be applied",
]

def find_project_root():
    """Find project root by looking for gradlew"""
    current = Path.cwd()
    while current != current.parent:
        if (current / "gradlew").exists() or (current / "gradlew.bat").exists():
            return current
        current = current.parent
    return Path.cwd()

def is_noise(line):
    """Check if line is gradle noise we should filter"""
    return any(pattern in line for pattern in NOISE_PATTERNS)

def is_error_line(line):
    """Check if line contains an error indicator"""
    return any(re.search(pattern, line, re.IGNORECASE) for pattern in ERROR_INDICATORS)

def extract_file_location(line):
    """Extract filename:line from a Java error line"""
    # Match: /path/to/File.java:123: error: message
    match = re.search(r'([A-Za-z0-9_]+\.java):(\d+):', line)
    if match:
        return f"{match.group(1)}:{match.group(2)}"
    return None

def run_gradle(tasks):
    """Run gradle with given tasks and capture output"""
    project_root = find_project_root()
    os.chdir(project_root)

    if isinstance(tasks, str):
        tasks = [tasks]

    cmd = [GRADLEW] + tasks + ["--no-daemon"]

    print(f"Running: {' '.join(cmd)}")
    print("-" * 60)

    process = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        shell=(os.name == "nt")
    )

    output_lines = []
    for line in process.stdout:
        output_lines.append(line.rstrip())

    process.wait()
    return process.returncode, output_lines

def process_output(lines, return_code):
    """Process gradle output and print relevant info"""

    if return_code == 0:
        # Success - just show success message
        print("=" * 60)
        print("BUILD SUCCESS")
        print("=" * 60)
        return

    # Build failed - extract and show errors
    print("=" * 60)
    print("BUILD FAILED")
    print("=" * 60)

    errors = []
    in_error_block = False
    error_block = []
    seen_errors = set()

    i = 0
    while i < len(lines):
        line = lines[i]

        # Skip empty lines and noise
        if not line.strip() or is_noise(line):
            i += 1
            continue

        # Detect start of error
        if is_error_line(line) and not is_noise(line):
            # Extract location if present
            loc = extract_file_location(line)

            # Clean up the error message
            error_msg = line.strip()

            # Remove full paths, keep just filename
            error_msg = re.sub(r'[A-Z]:\\[^:]+\\([^\\]+\.java)', r'\1', error_msg)
            error_msg = re.sub(r'/[^:]+/([^/]+\.java)', r'\1', error_msg)

            # Deduplicate
            error_key = error_msg[:100]
            if error_key not in seen_errors:
                seen_errors.add(error_key)
                errors.append(error_msg)

                # Check for follow-up lines (symbol info, etc.)
                j = i + 1
                while j < len(lines) and j < i + 4:
                    follow = lines[j].strip()
                    if follow and not is_noise(follow):
                        if follow.startswith("symbol:") or follow.startswith("location:"):
                            errors.append(f"    {follow}")
                        elif follow.startswith("^"):
                            pass  # Skip caret lines
                        elif "required:" in follow or "found:" in follow:
                            errors.append(f"    {follow}")
                    j += 1

        i += 1

    # Print errors
    if errors:
        print("\nErrors found:")
        print("-" * 60)
        for err in errors[:30]:  # Limit to 30 errors
            print(err)
        if len(errors) > 30:
            print(f"\n... and {len(errors) - 30} more errors")
    else:
        # Couldn't parse errors - show last part of output
        print("\nCouldn't parse specific errors. Last 30 lines:")
        print("-" * 60)
        relevant_lines = [l for l in lines if l.strip() and not is_noise(l)]
        for line in relevant_lines[-30:]:
            print(line)

    print("-" * 60)
    print(f"Fix errors and rebuild")

def main():
    task_arg = sys.argv[1] if len(sys.argv) > 1 else "compile"

    # Map shortcuts to actual gradle tasks
    if task_arg == "compile":
        # Fast compile check - just compile, no jars
        tasks = [":common:compileJava", ":fabric:compileJava", ":neoforge:compileJava"]
    elif task_arg == "compile:fabric":
        tasks = [":common:compileJava", ":fabric:compileJava"]
    elif task_arg == "compile:neoforge":
        tasks = [":common:compileJava", ":neoforge:compileJava"]
    elif task_arg == "release":
        # Alias for shadowJar
        tasks = ["shadowJar"]
    else:
        tasks = [task_arg]

    return_code, lines = run_gradle(tasks)
    process_output(lines, return_code)

    return return_code

if __name__ == "__main__":
    sys.exit(main())
