#!/usr/bin/env bash


cur_dir=$(dirname "${BASH_SOURCE-$0}")
cur_dir="$(cd "${cur_dir}"; pwd)"

# We prefer java from the JAVA_HOME
if [[ -z "$JAVA_HOME" ]]; then
  JAVA="$JAVA_HOME/bin/java"
  echo "JAVA_HOME is set to $JAVA_HOME"
else
  if [[ -z "$(which java)" ]]; then
    echo "java command not found"
    exit 1
  fi
  JAVA=java
  # when JAVA_HOME is not set, we will set it
  JAVA_HOME=${JAVA_HOME:-"$(dirname $(which java))/.."}
  echo "Warning: JAVA_HOME env is not set, inferring it to be $JAVA_HOME"
fi


VERSION=1.0-SNAPSHOT
RC_HOME=$(dirname "${cur_dir}")
RC_JAR="${RC_HOME}/target/RecoveryChecker-${VERSION}-jar-with-dependencies.jar"
SOOT_JAR=""
RC_MAIN=edu.uva.liftlab.pilot.RCMain

RC_CONF_DIR="${RC_CONF_DIR:-${RC_HOME}/conf}"
RC_LOGS_DIR="${RC_LOGS_DIR:-${RC_HOME}/logs}"
#
RC_JAVA_OPTS+=" -Drc.conf.dir=${RC_CONF_DIR} -Drc.logs.dir=${RC_LOGS_DIR}"
RC_JAVA_OPTS+=" -Dlog4j.configuration=file:${RC_CONF_DIR}/log4j.properties"
RC_JAVA_OPTS+=" -Dlog4j.rootLogger=WARN,CONSOLE,FILE"

RC_CLASSPATH="${RC_CONF_DIR}:${RC_JAR}"

# Expand SOOT_CLASSPATH wildcards
echo "Expanding SOOT_CLASSPATH..."

# Save original
ORIGINAL_SOOT_CLASSPATH="$SOOT_CLASSPATH"

# Build expanded classpath
EXPANDED_CLASSPATH=""

# Split on colon
IFS=':' read -ra PATHS <<< "$SOOT_CLASSPATH"

for path in "${PATHS[@]}"; do
  # Skip empty paths
  [[ -z "$path" ]] && continue

  # If path contains wildcard
  if [[ "$path" == *"*"* ]]; then
    # Use bash globbing to expand
    shopt -s nullglob
    for expanded in $path; do
      if [[ -n "$EXPANDED_CLASSPATH" ]]; then
        EXPANDED_CLASSPATH="${EXPANDED_CLASSPATH}:${expanded}"
      else
        EXPANDED_CLASSPATH="${expanded}"
      fi
    done
    shopt -u nullglob
  else
    # No wildcard, add as-is if exists
    if [[ -e "$path" ]]; then
      if [[ -n "$EXPANDED_CLASSPATH" ]]; then
        EXPANDED_CLASSPATH="${EXPANDED_CLASSPATH}:${path}"
      else
        EXPANDED_CLASSPATH="${path}"
      fi
    fi
  fi
done

# Update SOOT_CLASSPATH with expanded version

SOOT_CLASSPATH="$EXPANDED_CLASSPATH"

echo "Expanded SOOT_CLASSPATH: $SOOT_CLASSPATH"

SOOT_CLASSPATH="$SOOT_CLASSPATH:$(find "${RC_HOME}/lib" -name "*.jar" | tr '\n' ':')"

# Echo the final combined classpath to verify
echo "Final SOOT_CLASSPATH: $SOOT_CLASSPATH"

