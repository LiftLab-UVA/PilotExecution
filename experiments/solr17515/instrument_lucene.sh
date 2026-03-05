#!/bin/bash

# Get and save current directory
CURRENT_DIR=$(pwd)
echo "Current directory: $CURRENT_DIR"

cd "$HOME/PilotExecution" || {
    echo "Error: Unable to change to directory"
    exit 1
}

echo "Changed to directory: $(pwd)"

if [ ! -f "./run_engine.sh" ]; then
    echo "Error: run_engine.sh file does not exist in current directory"
    exit 1
fi

if [ ! -f "./experiments/solr17515/conf/lucene.properties" ]; then
    echo "Error: lucene.properties file does not exist"
    exit 1
fi

. ./run_engine.sh transform ./experiments/solr17515/conf/lucene.properties

# Get command execution result
EXIT_CODE=$?

if [ $EXIT_CODE -eq 0 ]; then
    echo "Command executed successfully"
else
    echo "Command execution failed, exit code: $EXIT_CODE"
fi

# Optional: Return to original directory
# cd "$CURRENT_DIR"
# echo "Returned to original directory: $(pwd)"

LOCAL_PATH="$HOME/PilotExecution/sootOutput"
rm -rf "$HOME/lucene_instrumented"
mkdir -p "$HOME/lucene_instrumented"
cp -r "$LOCAL_PATH" "$HOME/lucene_instrumented/"

exit $EXIT_CODE