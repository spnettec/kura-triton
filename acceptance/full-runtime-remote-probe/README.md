# macOS Triton remote SCR acceptance

Opt-in helper, outside the default reactor. It adds the current production Triton
bundle and this observer to an existing complete Mac application, using a new owned
configuration directory/profile and random HTTP ports. It never resets a personal
profile or rebuilds the source runtime. No source bundle is overlaid or patched.

The actual ConfigurationService creates a remote factory configuration. Actual SCR,
host SystemService, CryptoService and PrivilegedExecutorService are required;
the remote implementation issues no commands. The helper verifies service arrival,
gRPC transport construction, absent-server readiness returning false, configuration
changes, replaced/invalid/deleted channel termination, and cleanup. Reflection only
observes the private production channels and bound references. No fake gRPC stub,
executor, crypto or SystemService is registered.

Invalid native configurations also exercise real factory/SCR arrival and deletion,
requiring that no instance manager or channel is created. Invalid container
configuration uses an existing production orchestration provider when available;
an absent provider is explicitly recorded as unverified. No command, native
Triton process or container is launched in either case.

This does not run a Triton server or claim real inference, model loading, metrics,
GPU, native manager, container or installed Debian acceptance.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  /opt/homebrew/Cellar/maven/3.10.0/libexec/bin/mvn -B -f pom.xml \
  -Dmaven.repo.local=/Users/heyoulin/iot-kura-develop/migration-m2 package
python3 run.py --runtime /absolute/complete-runtime \
  --template-profile /absolute/owned-template-profile --archive /absolute/new-archive \
  --java /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java \
  --triton-bundle /absolute/current-triton-bundle.jar
```

Keep failed and successful result/log archives separate. Results are runtime
assertions and overlap existing unit coverage; do not add them to JUnit totals.
