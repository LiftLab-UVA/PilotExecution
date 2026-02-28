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

if [ ! -f "./experiments/solr17515/conf/solrj.properties" ]; then
    echo "Error: solrj.properties file does not exist"
    exit 1
fi

echo "Executing: . ./run_engine.sh transform ../conf/solrj.properties"
. ./run_engine.sh transform ./experiments/solr17515/conf/solrj.properties

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
rm -rf "$HOME/solrj_instrumented"
mkdir -p "$HOME/solrj_instrumented"
cp -r "$LOCAL_PATH" "$HOME/solrj_instrumented/"


exit $EXIT_CODE