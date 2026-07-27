#!/usr/bin/env bash

set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

EXPECTED_ARTIFACT_ID="DBRepro"
EXPECTED_MAIN_CLASS="ruc.db.rsgen.RSGenMainCLI"
DBREPRO_MAIN_CLASS="ruc.db.DBReproApp"

if ! command -v java >/dev/null 2>&1; then
    echo "Error: java is not installed or not available on PATH." >&2
    exit 1
fi

if ! command -v mvn >/dev/null 2>&1; then
    echo "Error: Maven is not installed or not available on PATH." >&2
    exit 1
fi

JAVA_VERSION="$(java -version 2>&1 | awk -F '"' 'NR == 1 {print $2}')"
JAVA_MAJOR="${JAVA_VERSION%%.*}"
if [[ "$JAVA_MAJOR" == "1" ]]; then
    JAVA_MAJOR="$(cut -d. -f2 <<<"$JAVA_VERSION")"
fi
if [[ ! "$JAVA_MAJOR" =~ ^[0-9]+$ ]] || (( JAVA_MAJOR < 21 )); then
    echo "Error: DBRepro requires Java 21 or newer; found ${JAVA_VERSION}." >&2
    exit 1
fi

ARTIFACT_ID="$(mvn -q -DforceStdout help:evaluate -Dexpression=project.artifactId)"
PROJECT_VERSION="$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version)"
if [[ "$ARTIFACT_ID" != "$EXPECTED_ARTIFACT_ID" ]]; then
    echo "Error: expected Maven artifactId ${EXPECTED_ARTIFACT_ID}, found ${ARTIFACT_ID}." >&2
    exit 1
fi

JAR_FILE="target/${ARTIFACT_ID}-${PROJECT_VERSION}.jar"

echo "Building ${ARTIFACT_ID} ${PROJECT_VERSION} with Java ${JAVA_VERSION}"
echo "[1/4] Cleaning and compiling sources"
mvn clean compile

echo "[2/4] Building the shaded JAR"
mvn package -DskipTests

if [[ ! -f "$JAR_FILE" ]]; then
    echo "Error: expected JAR was not created: ${JAR_FILE}" >&2
    exit 1
fi

echo "[3/4] Verifying packaged entry points"
jar tf "$JAR_FILE" | grep -qx "${EXPECTED_MAIN_CLASS//./\/}.class"
jar tf "$JAR_FILE" | grep -qx "${DBREPRO_MAIN_CLASS//./\/}.class"
java -jar "$JAR_FILE" --help >/dev/null
java -cp "${JAR_FILE}:lib/*" "$DBREPRO_MAIN_CLASS" --help >/dev/null

echo "[4/4] Build result"
ls -lh "$JAR_FILE"
sha256sum "$JAR_FILE"
echo "DBRepro JAR build and entry-point verification succeeded."
