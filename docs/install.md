# Installing NQ

NQ — SQL for nested data is distributed as native executables for macOS ARM64,
Linux x64, and Windows x64. A JVM build is also available for systems with JDK
25.

The commands below target NQ 0.9.3, the release against which this guide was
verified. Check the [latest release](https://github.com/blater/nq/releases/latest)
before installing.

## Support matrix

| Platform | Architecture | Package | Included JDBC drivers | Runtime loading of driver JARs |
|---|---|---|---|---|
| macOS | ARM64 | Homebrew or release `.tar.gz` | H2, MySQL, MariaDB, PostgreSQL | No |
| Linux | x64 | Release `.tar.gz` | H2, MySQL, MariaDB, PostgreSQL | No |
| Windows | x64 | Release `.zip` | H2, MySQL, MariaDB, PostgreSQL | No |
| JVM | Any JDK 25 platform | Fat JAR | Depends on build profile | Yes, through an explicit classpath |

Oracle, SQL Server, Db2, SAP HANA, and Informix native builds are not currently
published as release assets. Build the `jdbc-enterprise` or `jdbc-all` profile
from source when those drivers are required.

## macOS ARM64

Homebrew is the supported installation path:

```bash
brew install blater/tap/nq
nq --help
```

Upgrade with:

```bash
brew update
brew upgrade nq
```

The Homebrew formula is generated from the macOS ARM64 release artifact. Linux
Homebrew is not currently claimed as supported because it has not completed the
clean-environment verification matrix.

## Linux x64

```bash
NQ_VERSION=0.9.3
curl -fLO "https://github.com/blater/nq/releases/download/v${NQ_VERSION}/nq-${NQ_VERSION}-linux-x64.tar.gz"
curl -fLO "https://github.com/blater/nq/releases/download/v${NQ_VERSION}/SHA256SUMS"
grep "nq-${NQ_VERSION}-linux-x64.tar.gz" SHA256SUMS | sha256sum --check
tar -xzf "nq-${NQ_VERSION}-linux-x64.tar.gz"
install -m 0755 nq "$HOME/.local/bin/nq"
nq --help
```

Ensure `$HOME/.local/bin` is on `PATH`. Installing under `/usr/local/bin`
instead may require administrator privileges.

## Windows x64

In PowerShell:

```powershell
$version = "0.9.3"
$asset = "nq-$version-windows-x64.zip"
$release = "https://github.com/blater/nq/releases/download/v$version"
Invoke-WebRequest "$release/$asset" -OutFile $asset
Invoke-WebRequest "$release/SHA256SUMS" -OutFile SHA256SUMS
$expected = ((Select-String $asset SHA256SUMS).Line -split '\s+')[0].ToLower()
$actual = (Get-FileHash $asset -Algorithm SHA256).Hash.ToLower()
if ($actual -ne $expected) { throw "Checksum verification failed" }
Expand-Archive $asset -DestinationPath .\nq
.\nq\nq.exe --help
```

Move `nq.exe` into a directory on `PATH` for use outside the extracted folder.
A WinGet, Scoop, or Chocolatey package is not currently published.

## Operating-system security prompts

Release archives include SHA-256 checksums but the native executables are not
currently code-signed. macOS Gatekeeper or Windows SmartScreen may therefore
show an unknown-developer warning.

Verify that the archive hash matches `SHA256SUMS` downloaded from the same
GitHub release. Prefer an operating-system UI exception for the single verified
binary if one is required. Do not disable Gatekeeper, SmartScreen, antivirus,
or execution policy globally.

Check the latest release notes for the current signing status.

## JVM build

JDK 25 and Maven are required:

```bash
git clone https://github.com/blater/nq.git
cd nq
mvn test
mvn package
java -jar target/nq-*.jar --help
```

Build profiles select JDBC drivers:

```bash
mvn -Pjdbc-common package
mvn -Pjdbc-enterprise package
mvn -Pjdbc-all package
```

- `jdbc-common`: H2, MySQL, MariaDB, and PostgreSQL.
- `jdbc-enterprise`: Oracle, SQL Server, Db2, SAP HANA, and Informix, plus H2
  for cache support.
- `jdbc-all`: all common and enterprise drivers.

The JVM build can load an additional JDBC driver JAR at runtime. Native
executables cannot. See
[Supplying a JDBC Driver JAR](user-manual.md#supplying-a-jdbc-driver-jar).

## Shell completion

Completion definitions live in [`utils/completions`](../utils/completions/README.md) for
Bash, Zsh, and Fish.

## Verify first use

```bash
curl -sSLO https://raw.githubusercontent.com/blater/nq/master/docs/examples/customers.json
nq "select id, name from customers where city = 'London' order by id;" customers.json
```

Expected output:

```json
[{"id":"1","name":"Alice"},{"id":"3","name":"Eva"}]
```

If this fails, consult [Troubleshooting](troubleshooting.md) and include the NQ
version, operating system, architecture, command, stdout, and stderr in a bug
report.
