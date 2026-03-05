#!/bin/bash

# Check parameters
if [ $# -lt 1 ]; then
    echo "Usage: $0 <pilot|normal>"
    echo "  pilot  - Execute recovery command with &pilot=true suffix"
    echo "  normal - Execute recovery command directly"
    exit 1
fi

MODE="$1"

# Check if /opt/recoverycmd exists
if [ ! -f /opt/recoverycmd ]; then
    echo "❌ Error: /opt/recoverycmd file does not exist"
    exit 1
fi

# Read recovery command
RECOVERY_CMD=$(cat /opt/recoverycmd)

if [ -z "$RECOVERY_CMD" ]; then
    echo "❌ Error: /opt/recoverycmd is empty"
    exit 1
fi

echo "Original command: $RECOVERY_CMD"

# Extract URL from RECOVERY_CMD
# Format: curl http://${FOLLOWER_NODE}:8983/solr/admin/cores?action=REQUESTRECOVERY&core=${FOLLOWER_CORE}
URL=$(echo "$RECOVERY_CMD" | sed -n 's/curl \(.*\)/\1/p')

# Extract FOLLOWER_NODE from URL
FOLLOWER_NODE=$(echo "$URL" | sed -n 's/.*http:\/\/\([^:]*\):8983.*/\1/p')

if [ -z "$FOLLOWER_NODE" ]; then
    echo "❌ Error: Unable to extract FOLLOWER_NODE from command"
    exit 1
fi

echo "Extracted FOLLOWER_NODE: $FOLLOWER_NODE"

# Function to backup trace files on nodes

# Function to search logs
search_log() {
    local node="$1"
    ssh "$node" 'bash -s' << 'EOF'
LOG_FILE="/opt/Solr/solr/server/logs/solr.log"

if [ ! -f "$LOG_FILE" ]; then
    echo "❌ Error: Log file $LOG_FILE does not exist"
    exit 1
fi

# Get total number of lines in log
TOTAL_LINES=$(wc -l < "$LOG_FILE")

# Search from bottom to top
ERROR_LINE=0
SUCCESS_LINE=0

# Find line number of "Error while trying to recover" (first from bottom)
ERROR_LINE=$(tac "$LOG_FILE" | grep -n "Error while trying to recover" | head -1 | cut -d: -f1)
if [ -n "$ERROR_LINE" ]; then
    ERROR_LINE=$((TOTAL_LINES - ERROR_LINE + 1))
fi

# Find line number of "Finished recovery process, successful=[true]" (first from bottom)
SUCCESS_LINE=$(tac "$LOG_FILE" | grep -Fn "Finished recovery process, successful=[true]" | head -1 | cut -d: -f1)
if [ -n "$SUCCESS_LINE" ]; then
    SUCCESS_LINE=$((TOTAL_LINES - SUCCESS_LINE + 1))
fi

# Determine which appears first (larger line number is closer to end of file)
if [ -n "$ERROR_LINE" ] && [ -n "$SUCCESS_LINE" ]; then
    if [ "$ERROR_LINE" -gt "$SUCCESS_LINE" ]; then
        # Error appears after Success (closer to end)
        echo "Error detected"
        sed -n "${ERROR_LINE},$((ERROR_LINE+5))p" "$LOG_FILE"
    else
        # Success appears after Error (closer to end)
        echo "Recovery successful:"
        sed -n "${SUCCESS_LINE}p" "$LOG_FILE"
    fi
elif [ -n "$ERROR_LINE" ]; then
    # Only found Error
    echo "Error detected"
    sed -n "${ERROR_LINE},$((ERROR_LINE+5))p" "$LOG_FILE"
elif [ -n "$SUCCESS_LINE" ]; then
    # Only found Success
    echo "Recovery successful:"
    sed -n "${SUCCESS_LINE}p" "$LOG_FILE"
else
    echo "No recovery-related logs found"
fi
EOF
}

# Execute based on parameter
if [ "$MODE" = "pilot" ]; then
    # Add pilot=true suffix, wrap URL in double quotes
    EXEC_CMD="curl \"${URL}&pilot=true\""
    echo "Execution mode: pilot"
    echo "Executing command: $EXEC_CMD"
    eval "$EXEC_CMD" > /dev/null 2>&1
    sleep 5

    java -jar $HOME/PilotExecution/Pilot/target/Pilot-1.0-SNAPSHOT.jar report

elif [ "$MODE" = "normal" ]; then
    # Execute original command directly, wrap URL in double quotes
    EXEC_CMD="curl \"${URL}\""
    echo "Execution mode: normal"
    echo "Executing command: $EXEC_CMD"
    eval "$EXEC_CMD" > /dev/null 2>&1

    # Wait for recovery to complete
    sleep 5

    echo ""
    echo "========================================"
    echo "     NORMAL execution results"
    echo "========================================"
    echo ""

    search_log "$FOLLOWER_NODE"

else
    echo "❌ Error: Invalid parameter '$MODE'"
    echo "Please use 'pilot' or 'normal'"
    exit 1
fi