#!/usr/bin/env python3
"""The emulator device matrix in .github/devices/matrix.json: when it runs, which legs, how their
AVDs are set up, and what they found. .github/workflows/devices.yml and .github/tools/device-run.sh
use it. Only the standard library, so it runs on the stock runner and on a PC.

  device-matrix.py tier --event E [--action A] [--input-tier T] [--label L] [--labels JSON]
                        [--repo OWNER/NAME] [--switch VALUE] [--github-output]
  device-matrix.py plan --tier none|core|wide|release [--legs a,b] [--check-sdk SDK]
                        [--github-output]
  device-matrix.py leg-env (--leg ID | --leg-json FILE) [--plain]
  device-matrix.py avd-config LEG.json [--avd-home DIR]
  device-matrix.py cache-key LEG.json --sdk SDK
  device-matrix.py verdict DIR [--leg-json FILE] [--summary FILE]
  device-matrix.py summary DIR...

When it runs (tier): on the public repo, pull requests run core, the weekly schedule wide and v*
tags release. Anywhere else (the private repo pays for its minutes) only a run by hand or a call
from another workflow runs anything, and a pull request only when it has the device-tests label.
A repository variable STARWIRE_DEVICE_TESTS=off (--switch) stops the automatic runs everywhere.

Exit codes: 0 fine, 1 a leg failed (verdict), 2 bad input or a problem in the matrix.
"""
import argparse
import hashlib
import json
import os
import re
import shlex
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MATRIX = os.path.join(ROOT, ".github", "devices", "matrix.json")
TEST_SOURCES = os.path.join(ROOT, "app", "src", "androidTest", "java")
PUBLIC_REPO = "SicarioGaming/Starwire"
LABEL = "device-tests"
TIERS = ["core", "wide", "release"]
TARGETS = {"default", "google_apis", "google_apis_ps16k", "aosp_atd", "google_atd", "android-tv", "google-tv"}
ARCHES = {"x86", "x86_64"}  # what KVM on the ubuntu runners can run
VARIANT = re.compile(r"^(font-\d(\.\d+)?|display-largest|dark|rtl-[a-z]{2,3}|landscape|portrait|gamepad|dpad|tv-launcher)$")
POSTURES = ("folded", "half-open", "unfolded")
HARDWARE_KEY = re.compile(r"^hw\.[A-Za-z0-9_.]+$")
# The AVD's data partition. Left unset, the emulator 37 on the runners wanted 12288 MB free to make it,
# more than a 2-vCPU runner has, and quit with "Not enough space to create userdata partition".
DISK = "4096M"
QUIT_NOTE = "emulator-quit.txt"  # device-watch.sh leaves it when the emulator quits before booting
LEG_ID = re.compile(r"^[a-z0-9][a-z0-9-]{1,40}$")
CLASS = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*\.[A-Z][A-Za-z0-9_]*$")
# device-test.sh's and device-run.sh's exit codes
CODES = {0: "PASS", 1: "FAIL", 2: "CRASH", 3: "INSTALL", 4: "DEVICE"}


def die(msg):
    print(f"device-matrix: {msg}", file=sys.stderr)
    sys.exit(2)


def write_outputs(values, github_output):
    lines = "".join(f"{k}={v}\n" for k, v in values.items())
    target = os.environ.get("GITHUB_OUTPUT") if github_output else None
    if target:
        with open(target, "a", encoding="utf-8") as f:
            f.write(lines)
    sys.stdout.write(lines)


# ---------------------------------------------------------------------------------------- tier
def decide_tier(event, action="", input_tier="", label="", labels=(), repo="", switch=""):
    """The tier a run gets, and why."""
    if input_tier:
        if input_tier not in TIERS:
            die(f"tier must be one of {TIERS}, not {input_tier}")
        return input_tier, "asked for by hand or by the calling workflow"
    if switch == "off":
        return "none", "the repository variable STARWIRE_DEVICE_TESTS is off"
    public = repo == PUBLIC_REPO
    if event == "pull_request":
        if action == "labeled":
            if label == LABEL:
                return "core", f"the {LABEL} label was added"
            return "none", "a label other than device-tests was added"
        if public:
            return "core", "a pull request on the public repo"
        if LABEL in labels:
            return "core", f"the pull request has the {LABEL} label"
        return "none", f"pull requests here only run the devices with the {LABEL} label"
    if event == "schedule":
        return ("wide", "the weekly run") if public else ("none", "the weekly run is only on the public repo")
    if event == "push":
        return ("release", "a release tag") if public else ("none", "tags only run the devices on the public repo")
    if event == "workflow_dispatch":
        return "core", "a run by hand"
    return "none", f"nothing runs on {event or 'this event'}"


def cmd_tier(a):
    try:
        labels = json.loads(a.labels) if a.labels.strip() else []
    except ValueError:
        die(f"--labels must be a JSON list, not {a.labels}")
    tier, why = decide_tier(a.event, a.action, a.input_tier, a.label, labels or [], a.repo, a.switch)
    print(f"Tier {tier}: {why}.", file=sys.stderr)
    write_outputs({"tier": tier}, a.github_output)


# ------------------------------------------------------------------------------------- matrix
def load_matrix(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError) as e:
        die(f"{path}: {e}")


def variant_tests(path):
    """The layout test classes each posture and variant pass reruns."""
    tests = load_matrix(path).get("variant_tests", [])
    bad = [t for t in tests if not isinstance(t, str) or not CLASS.match(t)]
    if not tests or bad:
        die(f"{path}: variant_tests must list test classes like com.limelight.ScreenWidthTest (bad: {bad})")
    if os.path.isdir(TEST_SOURCES):
        missing = [t for t in tests if not os.path.exists(os.path.join(TEST_SOURCES, *t.split(".")) + ".java")]
        if missing:
            die(f"{path}: variant_tests names classes that don't exist: {', '.join(missing)}")
    return tests


def api_major(api):
    return int(api.split(".")[0])


def check_leg(leg):
    p = []
    for key in ("id", "name", "tier", "why", "profile", "api", "target", "arch"):
        if not isinstance(leg.get(key), str) or not leg[key]:
            p.append(f"{key} must be a non-empty string")
    if p:
        return p
    if not LEG_ID.match(leg["id"]):
        p.append("id: lower case letters, digits and dashes")
    if leg["tier"] not in TIERS:
        p.append(f"tier must be one of {TIERS}")
    if leg["target"] not in TARGETS:
        p.append(f"target {leg['target']} unknown")
    if leg["arch"] not in ARCHES:
        p.append(f"arch {leg['arch']}: the runners only accelerate x86 and x86_64")
    if not re.match(r"^\d+(\.\d+)?$", leg["api"]):
        p.append("api must look like 35 or 37.0")
        return p
    if not re.match(r"^\d+M$", str(leg.get("ram", "2048M"))):
        p.append("ram must look like 2048M")
    if not re.match(r"^\d+M$", str(leg.get("disk", DISK))):
        p.append("disk must look like 4096M")
    for key in ("cache", "experimental"):
        if not isinstance(leg.get(key, False), bool):
            p.append(f"{key} must be true or false")
    variants = leg.get("variants", [])
    postures = leg.get("postures", [])
    for v in variants:
        if not isinstance(v, str) or not VARIANT.match(v):
            p.append(f"unknown variant {v}")
    for s in postures:
        if s not in POSTURES:
            p.append(f"unknown posture {s} (one of {', '.join(POSTURES)})")
    if len(set(variants)) != len(variants) or len(set(postures)) != len(postures):
        p.append("a variant or posture is listed twice")
    for k, v in leg.get("hardware", {}).items():
        if not HARDWARE_KEY.match(k) or not re.match(r"^[A-Za-z0-9_.-]+$", str(v)):
            p.append(f"hardware {k}={v} is not a plain config.ini entry")
    monkey = leg.get("monkey", 0)
    if not isinstance(monkey, int) or isinstance(monkey, bool) or monkey < 0:
        p.append("monkey must be a whole number of events")
    elif monkey and api_major(leg["api"]) < 30:
        # device-test.sh only switches an emulator to airplane mode for it from Android 11
        p.append("monkey needs api 30 or later, where device-test.sh can turn the network off")
    if leg.get("install_abi") not in (None, "arm64-v8a", "armeabi-v7a"):
        p.append("install_abi must be arm64-v8a or armeabi-v7a")
    return p


def load_legs(path):
    legs = load_matrix(path).get("legs")
    if not isinstance(legs, list) or not legs:
        die(f"{path}: no legs")
    seen, problems = set(), []
    for leg in legs:
        if not isinstance(leg, dict):
            problems.append(f"{leg!r} is not an object")
            continue
        p = check_leg(leg)
        if leg.get("id") in seen:
            p.append("duplicate id")
        seen.add(leg.get("id"))
        problems += [f"leg {leg.get('id', '?')}: {x}" for x in p]
    if problems:
        die(f"{path}:\n  " + "\n  ".join(problems))
    return legs


def image(leg):
    return f"system-images;android-{leg['api']};{leg['target']};{leg['arch']}"


def leg_by_id(path, leg_id):
    legs = {l["id"]: l for l in load_legs(path)}
    if leg_id not in legs:
        die(f"no leg {leg_id} in {path} (there are: {', '.join(legs)})")
    return legs[leg_id]


def read_entry(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError) as e:
        die(f"{path}: {e}")


# ---------------------------------------------------------------------------------------- plan
def cmd_plan(a):
    legs = load_legs(a.matrix)
    variant_tests(a.matrix)
    if a.legs.strip():
        wanted = [x.strip() for x in a.legs.split(",") if x.strip()]
        unknown = [x for x in wanted if x not in {l["id"] for l in legs}]
        if unknown:
            die("unknown legs: " + ", ".join(unknown))
        chosen = [l for l in legs if l["id"] in wanted]  # --legs overrides the tier
    elif a.tier == "none":
        chosen = []
    elif a.tier in TIERS:
        rank = TIERS.index(a.tier)
        chosen = [l for l in legs if TIERS.index(l["tier"]) <= rank]
    else:
        die(f"tier must be none or one of {TIERS}")
    if a.check_sdk and chosen:
        check_sdk(a.check_sdk, chosen)
    include = [{
        "id": l["id"], "name": l["name"], "profile": l["profile"], "api": l["api"], "target": l["target"],
        "arch": l["arch"], "ram": l.get("ram", "2048M"), "cache": l.get("cache", False),
        "experimental": l.get("experimental", False),
    } for l in chosen]
    write_outputs({"matrix": json.dumps({"include": include}, separators=(",", ":")), "count": str(len(include)),
                   "legs": ",".join(l["id"] for l in chosen)}, a.github_output)


ANSI = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]")
LIST_HEADING = re.compile(r"^(installed|available)( obsolete)? (packages|updates):$", re.I)
PACKAGE_PATH = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*([;/][A-Za-z0-9._-]+)*$")


def listed_packages(listing):
    """The package paths in the output of sdkmanager --list, with ; between their parts.

    Command-line tools 22.0 and older print a table: "  system-images;android-35;google_apis;x86_64 | 9 | ...".
    From 23.0 sdkmanager is a shell script that runs the Android CLI's "android sdk list --all", which
    prints "  system-images/android-35/google_apis/x86_64     9.0.0     ...": / instead of ;, no | between
    the columns, and colour codes around the installed packages. Only indented lines under a heading
    like "Installed packages:" or "Available Packages:" count, not the progress bar, warnings or terms."""
    packages, listed = set(), False
    for line in listing.splitlines():
        line = ANSI.sub("", line).rstrip()
        if not line.strip():
            continue
        if not line[0].isspace():
            listed = bool(LIST_HEADING.match(line))
            continue
        first = line.split("|")[0].split()
        if listed and first and PACKAGE_PATH.match(first[0]) and first[0] not in ("Path", "ID"):
            packages.add(first[0].replace("/", ";"))
    return packages


def tool(bin_dir, name):
    """A command-line tool in the SDK; on Windows it's the .bat."""
    path = os.path.join(bin_dir, name)
    return path + ".bat" if os.name == "nt" and os.path.exists(path + ".bat") else path


def check_sdk(sdk, legs):
    """Fails before any emulator starts when a leg names a profile or image that doesn't exist."""
    bin_dir = os.path.join(sdk, "cmdline-tools", "latest", "bin")
    devices = {d.strip() for d in run([tool(bin_dir, "avdmanager"), "list", "device", "-c"]).splitlines()}
    listing = run([tool(bin_dir, "sdkmanager"), "--list"])
    packages = listed_packages(listing)
    if not any(p.startswith("system-images;") for p in packages):
        # Output this can't read mustn't look like a matrix full of missing images
        shown = [l for l in (ANSI.sub("", l).rstrip() for l in listing.splitlines()) if l.strip() and l[0] != "["]
        print("sdkmanager --list printed:\n" + "\n".join(shown[:40]), file=sys.stderr)
        die(f"sdkmanager --list named no system images ({len(packages)} other packages): has its output changed?")
    print(f"sdkmanager --list named {len(packages)} packages", file=sys.stderr)
    bad = []
    for leg in legs:
        if leg["profile"] not in devices:
            bad.append(f"{leg['id']}: avdmanager has no device '{leg['profile']}'")
        if image(leg) not in packages:
            bad.append(f"{leg['id']}: sdkmanager has no {image(leg)}")
    if bad:
        for b in bad:
            print(f"::error title=Device matrix::{b}")
        die("the matrix names profiles or images that don't exist")


def run(argv):
    try:
        return subprocess.run(argv, check=True, capture_output=True, text=True, encoding="utf-8", errors="replace",
                              timeout=300).stdout
    except (OSError, subprocess.SubprocessError) as e:
        die(f"{' '.join(argv)} failed: {e}")


# ------------------------------------------------------------------------------------- leg-env
def cmd_leg_env(a):
    leg = leg_by_id(a.matrix, read_entry(a.leg_json)["id"] if a.leg_json else a.leg)
    env = {
        "LEG_ID": leg["id"], "LEG_NAME": leg["name"], "LEG_TIER": leg["tier"],
        "LEG_PROFILE": leg["profile"], "LEG_API": leg["api"], "LEG_TARGET": leg["target"], "LEG_ARCH": leg["arch"],
        "LEG_IMAGE": image(leg), "LEG_RAM": leg.get("ram", "2048M"), "LEG_DISK": leg.get("disk", DISK),
        "LEG_MONKEY": str(leg.get("monkey", 0)),
        "LEG_VARIANTS": " ".join(leg.get("variants", [])), "LEG_POSTURES": " ".join(leg.get("postures", [])),
        "LEG_INSTALL_ABI": leg.get("install_abi", ""),
        "LEG_EXPERIMENTAL": "true" if leg.get("experimental") else "false",
        "VARIANT_TESTS": ",".join(variant_tests(a.matrix)),
    }
    for k, v in env.items():
        print(f"{k}={v}" if a.plain else f"{k}={shlex.quote(v)}")


# ---------------------------------------------------------------------------------- avd-config
def config_key(line):
    return line.split("=", 1)[0].strip() if "=" in line and not line.lstrip().startswith("#") else None


def cmd_avd_config(a):
    """Sets the leg's data partition size and hardware overrides in its AVD (named after the leg id)
    before it boots, and leaves every key in config.ini once.

    The emulator action appends hw.cpu.ncore and hw.ramSize on every run, after what avdmanager wrote,
    so a key can be there twice; the last one is the one that was meant, and only that one is kept."""
    leg = leg_by_id(a.matrix, read_entry(a.leg_json)["id"])
    settings = dict(leg.get("hardware", {}))
    settings["disk.dataPartition.size"] = leg.get("disk", DISK)
    home = a.avd_home or os.environ.get("ANDROID_AVD_HOME") or os.path.expanduser("~/.android/avd")
    config = os.path.join(home, f"{leg['id']}.avd", "config.ini")
    if not os.path.exists(config):
        die(f"{config} not found")
    with open(config, encoding="utf-8") as f:
        lines = [l.rstrip("\r\n") for l in f]
    was = [l.split("=", 1)[1].strip() for l in lines if config_key(l) == "disk.dataPartition.size"]
    last = {config_key(l): i for i, l in enumerate(lines) if config_key(l)}
    kept = [l for i, l in enumerate(lines)
            if config_key(l) is None or (last[config_key(l)] == i and config_key(l) not in settings)]
    kept += [f"{k}={v}" for k, v in sorted(settings.items())]
    with open(config, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(kept) + "\n")
    print(f"{leg['id']}: set " + ", ".join(f"{k}={v}" for k, v in sorted(settings.items()))
          + f" (the data partition was {', '.join(was) or 'not set'})")


# ----------------------------------------------------------------------------------- cache-key
def revision(path):
    """Pkg.Revision from an installed SDK package's source.properties, or its package.xml."""
    props = os.path.join(path, "source.properties")
    if os.path.exists(props):
        with open(props, encoding="utf-8", errors="replace") as f:
            for line in f:
                if line.startswith("Pkg.Revision="):
                    return line.split("=", 1)[1].strip()
    xml = os.path.join(path, "package.xml")
    if os.path.exists(xml):
        with open(xml, encoding="utf-8", errors="replace") as f:
            m = re.search(r"<revision>(.*?)</revision>", f.read(), re.S)
        if m:
            return ".".join(re.findall(r"<(?:major|minor|micro)>(\d+)<", m.group(1)))
    return "none"


def cmd_cache_key(a):
    leg = leg_by_id(a.matrix, read_entry(a.leg_json)["id"])
    img = revision(os.path.join(a.sdk, "system-images", f"android-{leg['api']}", leg["target"], leg["arch"]))
    emu = revision(os.path.join(a.sdk, "emulator"))
    # Anything that changes the AVD or its snapshot changes the key
    shape = json.dumps({"profile": leg["profile"], "hardware": leg.get("hardware"), "ram": leg.get("ram"),
                        "disk": leg.get("disk", DISK)}, sort_keys=True)
    digest = hashlib.sha256(shape.encode()).hexdigest()[:10]
    print(f"key=avd-v1-{leg['id']}-img{img}-emu{emu}-{digest}")
    print(f"image_revision={img}")
    print(f"emulator_revision={emu}")


# ------------------------------------------------------------------------------ verdict/summary
def read_lines(path):
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8", errors="replace") as f:
        return [l.rstrip("\r\n") for l in f]


def read_result(d):
    """What device-run.sh left in one leg's folder."""
    r = {"dir": d, "facts": {}, "passes": [], "exit": None}
    for line in read_lines(os.path.join(d, "facts.env")):
        if "=" in line:
            k, v = line.split("=", 1)
            r["facts"][k] = v
    for line in read_lines(os.path.join(d, "passes.tsv")):
        cols = line.split("\t")
        if len(cols) >= 6:
            r["passes"].append(dict(zip(["name", "status", "passed", "failed", "skipped", "note"], cols[:6])))
    code = "".join(read_lines(os.path.join(d, "exit-code"))).strip()
    if code:
        r["exit"] = int(code) if code.isdigit() else 4
    r["failed_tests"] = [l for l in read_lines(os.path.join(d, "failed-tests.txt")) if l.strip()]
    r["quit"] = " ".join(l.strip() for l in read_lines(os.path.join(d, QUIT_NOTE)) if l.strip())
    return r


def status_of(r):
    if r["exit"] is None:
        return "ERROR" if os.path.exists(os.path.join(r["dir"], "booted")) else "NO BOOT"
    if r["exit"] == 0 and r["passes"] and all(p["status"] == "SKIP" for p in r["passes"]):
        return "SKIPPED"
    return CODES.get(r["exit"], "ERROR")


def ok_status(s):
    return s in ("PASS", "SKIPPED")


def leg_name(r):
    return r["facts"].get("LEG_NAME", os.path.basename(os.path.normpath(r["dir"])))


HEADER = ("| Leg | Device | Android | Tests passed / failed / skipped | Postures and variants | Boot | Result |\n"
          "|---|---|---|---|---|---|---|")
SHOWN = {"PASS": "pass", "SKIPPED": "skipped", "FAIL": "**fail**", "CRASH": "**crash**",
         "INSTALL": "**install failed**", "DEVICE": "**device problem**", "NO BOOT": "**did not boot**",
         "ERROR": "**error**"}


def row(r):
    f = r["facts"]
    leg = leg_name(r)
    if f.get("LEG_EXPERIMENTAL") == "true" and "experimental" not in leg:
        leg += " (experimental)"
    screen = f"{f.get('SIZE') or '?'} at {f.get('DENSITY') or '?'} dpi"
    android = f"API {f.get('SDK') or f.get('LEG_API', '?')} {f.get('LEG_TARGET', '')}".strip()
    base = next((p for p in r["passes"] if p["name"] == "base"), None)
    tests = f"{base['passed']} / {base['failed']} / {base['skipped']}" if base else "-"
    extras = ", ".join(f"{p['name']} {p['status'].lower()}" for p in r["passes"] if p["name"] != "base") or "-"
    return (f"| {leg} | {f.get('LEG_PROFILE', '?')}, {screen} | {android} | {tests} | {extras} | "
            f"{f.get('BOOT', '?')} | {SHOWN[status_of(r)]} |")


def problems(r):
    out = [f"- {r['quit']}"] if r["quit"] else []
    out += [f"- `{t}`" for t in r["failed_tests"][:30]]
    out += [f"- {p['name']}: {p['status']} {p['note']}".rstrip() for p in r["passes"] if p["status"] not in ("PASS", "SKIP")]
    return out


def cmd_verdict(a):
    r = read_result(a.dir)
    if a.leg_json and "LEG_NAME" not in r["facts"]:
        # The emulator never got as far as device-run.sh: name the leg from its matrix entry
        e = read_entry(a.leg_json)
        r["facts"].update({"LEG_ID": e["id"], "LEG_NAME": e["name"], "LEG_PROFILE": e["profile"],
                           "LEG_API": e["api"], "LEG_TARGET": e["target"],
                           "LEG_EXPERIMENTAL": "true" if e.get("experimental") else "false"})
    status = status_of(r)
    if a.summary:
        with open(a.summary, "a", encoding="utf-8") as f:
            f.write(HEADER + "\n" + row(r) + "\n")
            found = problems(r)
            if found:
                f.write("\n" + "\n".join(found) + "\n")
    print(row(r))
    if not ok_status(status):
        title = r["facts"].get("LEG_ID", "leg")
        if r["facts"].get("LEG_EXPERIMENTAL") == "true":
            print(f"::warning title={title}::experimental leg: {status}")
            return
        print(f"::error title={title}::{status}" + (f": {r['quit']}" if r["quit"] else ""))
        sys.exit(1)


def cmd_summary(a):
    dirs = []
    for top in a.dirs:
        for dirpath, _, files in os.walk(top):
            if "facts.env" in files or "booted" in files or "exit-code" in files:
                dirs.append(dirpath)
    results = sorted((read_result(d) for d in dirs), key=lambda r: r["facts"].get("LEG_ID", r["dir"]))
    print("## Emulator device matrix\n")
    if not results:
        print("No leg left any results (did every emulator fail to start?).")
        return
    print(HEADER)
    for r in results:
        print(row(r))
    bad = [r for r in results if not ok_status(status_of(r))]
    print(f"\n{len(results) - len(bad)} of {len(results)} legs passed or were skipped.")
    for r in bad:
        print(f"\n**{leg_name(r)}**: {status_of(r)}")
        print("\n".join(problems(r)[:20]))
    print("\nEach leg's logs, screenshots and logcat are in the run's `device-result-*` artifacts.")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--matrix", default=MATRIX)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("tier")
    p.add_argument("--event", required=True)
    p.add_argument("--action", default="")
    p.add_argument("--input-tier", default="")
    p.add_argument("--label", default="")
    p.add_argument("--labels", default="")
    p.add_argument("--repo", default="")
    p.add_argument("--switch", default="")
    p.add_argument("--github-output", action="store_true")
    p = sub.add_parser("plan")
    p.add_argument("--tier", required=True)
    p.add_argument("--legs", default="")
    p.add_argument("--check-sdk", default="")
    p.add_argument("--github-output", action="store_true")
    p = sub.add_parser("leg-env")
    g = p.add_mutually_exclusive_group(required=True)
    g.add_argument("--leg")
    g.add_argument("--leg-json")
    p.add_argument("--plain", action="store_true", help="KEY=value lines without shell quoting")
    p = sub.add_parser("avd-config")
    p.add_argument("leg_json")
    p.add_argument("--avd-home", default="")
    p = sub.add_parser("cache-key")
    p.add_argument("leg_json")
    p.add_argument("--sdk", required=True)
    p = sub.add_parser("verdict")
    p.add_argument("dir")
    p.add_argument("--leg-json", default="")
    p.add_argument("--summary", default="")
    p = sub.add_parser("summary")
    p.add_argument("dirs", nargs="+")
    a = ap.parse_args()
    {"tier": cmd_tier, "plan": cmd_plan, "leg-env": cmd_leg_env, "avd-config": cmd_avd_config,
     "cache-key": cmd_cache_key, "verdict": cmd_verdict, "summary": cmd_summary}[a.cmd](a)


if __name__ == "__main__":
    main()
