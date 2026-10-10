#!/usr/bin/env python3
"""Run only Triton remote SCR lifecycle on an owned macOS application/profile copy."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import socket
import subprocess
import time
import xml.etree.ElementTree as ET


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def available_ports(count):
    handles = []
    try:
        for _ in range(count):
            handle = socket.socket()
            handle.bind(("127.0.0.1", 0))
            handles.append(handle)
        return [handle.getsockname()[1] for handle in handles]
    finally:
        for handle in handles:
            handle.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("runtime", "template-profile", "archive", "java", "triton-bundle"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    for name in ("runtime", "template_profile", "archive", "java", "triton_bundle"):
        setattr(args, name, getattr(args, name).resolve())
    source_options = shlex.split((args.runtime / "jvm.args").read_text())
    homes = [x.removeprefix("-Dkura.home=") for x in source_options if x.startswith("-Dkura.home=")]
    if len(homes) != 1 or not Path(homes[0]).is_absolute():
        parser.error("Runtime must declare one absolute kura.home")
    protected = [Path.home() / ".kura-dev", args.runtime, args.template_profile, Path(homes[0])]
    if any(args.archive == p or p in args.archive.parents for p in protected):
        parser.error("Archive must be new and outside source runtime/profile and personal homes")
    inventory = json.loads((args.runtime / "inventory.json").read_text())
    if any(e["symbolicName"] == "org.eclipse.kura.ai.triton.server" for e in inventory):
        parser.error("This probe adds the Triton bundle explicitly; do not duplicate an existing bundle")
    module = Path(__file__).resolve().parent
    args.archive.mkdir(parents=True, exist_ok=False)
    profile = args.archive / "profile"
    shutil.copytree(args.template_profile, profile, ignore=shutil.ignore_patterns("logs", "tmp", "*-result.json"))
    (profile / "logs").mkdir()
    (profile / "tmp").mkdir()
    relocated = []
    for file in profile.rglob("*"):
        if file.is_file() and file.suffix in (".xml", ".properties", ".json"):
            data = file.read_bytes()
            if str(args.template_profile).encode() in data:
                file.write_bytes(data.replace(str(args.template_profile).encode(), str(profile).encode()))
                relocated.append(str(file.relative_to(profile)))
    (profile / ".triton-remote-acceptance-owned").write_text("Owned remote-only Mac SCR acceptance\n")
    ports = available_ports(3)
    snapshot = profile / "user/snapshots/snapshot_0.xml"
    tree = ET.parse(snapshot)
    http = next(e for e in tree.getroot().iter() if e.attrib.get("pid") == "org.eclipse.kura.http.server.manager.HttpService")
    names = ("http.ports", "https.ports", "https.client.auth.ports")
    for name, port in zip(names, ports):
        prop = next(e for e in http.iter() if e.attrib.get("name") == name)
        prop.find("{*}value").text = str(port)
    # Registered namespaces preserve the snapshot format in this owned profile only.
    for _, item in ET.iterparse(snapshot, events=("start-ns",)):
        ET.register_namespace(item[0], item[1])
    tree.write(snapshot, encoding="utf-8", xml_declaration=True)
    helper = args.archive / "triton-remote-probe.jar"
    production = args.archive / "triton-production.jar"
    shutil.copy2(module / "target/kura-full-runtime-triton-remote-acceptance-1.0.0-SNAPSHOT.jar", helper)
    shutil.copy2(args.triton_bundle, production)
    configuration = args.archive / "configuration"
    configuration.mkdir()

    def relocate(content):
        return (content.replace(str(args.template_profile), str(profile)).replace(homes[0], str(profile))
                .replace(str(args.runtime / "configuration"), str(configuration)))

    for file in (args.runtime / "configuration").iterdir():
        if file.is_file():
            text = relocate(file.read_text())
            if file.name == "config.ini":
                text = re.sub(r"(?m)^osgi.bundles=(.*)$", lambda m: m.group(0)
                              + ",reference:" + production.as_uri() + "@5:start"
                              + ",reference:" + helper.as_uri() + "@6:start", text)
            (configuration / file.name).write_text(text)
    options = [relocate(x) for x in source_options if not x.startswith("-Dorg.osgi.service.http.port=")]
    options.append("-Dorg.osgi.service.http.port=" + str(ports[0]))
    assert "-Dkura.home=" + str(profile) in options
    command = [str(args.java), *options, "-Dkura.acceptance.root=" + str(args.archive), "-jar",
               str(args.runtime / "launcher.jar"), "-configuration", str(configuration),
               "-install", str(args.runtime), "-console", "-consoleLog"]
    (args.archive / "command.json").write_text(json.dumps(command, indent=2) + "\n")
    (args.archive / "profile-relocation.json").write_text(json.dumps({"runtimeHome": homes[0],
        "templateProfile": str(args.template_profile), "ownedProfile": str(profile),
        "relocatedFiles": relocated, "ownedHttpPorts": ports}, indent=2) + "\n")
    started = time.monotonic()
    forced = False
    result = {"passed": False}
    with (args.archive / "console.log").open("w") as log:
        app = subprocess.Popen(command, cwd=args.runtime, stdin=subprocess.PIPE,
                               stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            output = args.archive / "triton-remote-result.json"
            deadline = time.monotonic() + 150
            while app.poll() is None and not output.exists() and time.monotonic() < deadline:
                time.sleep(0.25)
            result = json.loads(output.read_text()) if output.exists() else {"passed": False, "error": "No result before exit/150-second deadline"}
        finally:
            if app.poll() is None:
                os.killpg(app.pid, signal.SIGTERM)
                try:
                    app.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    forced = True
                    os.killpg(app.pid, signal.SIGKILL)
                    app.wait(timeout=5)
            if app.stdin and not app.stdin.closed:
                app.stdin.close()
    released = True
    for port in ports:
        try:
            with socket.socket() as handle:
                handle.bind(("127.0.0.1", port))
        except OSError:
            released = False
    result.update(elapsedSeconds=round(time.monotonic() - started, 3), pid=app.pid,
                  exitCode=app.returncode, forcedShutdown=forced, portsReleased=released,
                  productionBundleSha256=sha(production), helperSha256=sha(helper),
                  runtimeInventorySha256=sha(args.runtime / "inventory.json"),
                  sources={str(p.relative_to(module)): sha(p) for p in module.rglob("*")
                           if p.is_file() and "target" not in p.relative_to(module).parts},
                  scope="Actual remote SCR/configuration and gRPC channel lifecycle on Mac; no Triton server, GPU, native command or container started")
    if forced or not released:
        result.update(passed=False, error="Owned process/ports cleanup failed")
    result["logs"] = {str(p.relative_to(args.archive)): sha(p) for p in
                      [args.archive / "console.log", profile / "logs/kura.log"] if p.is_file()}
    (args.archive / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({k: result.get(k) for k in ["passed", "error", "elapsedSeconds", "forcedShutdown", "portsReleased"]}), flush=True)
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
