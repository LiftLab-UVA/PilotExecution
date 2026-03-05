#!/bin/bash

set -e

NODES=("node0" "node1")

usage() {
    echo "Usage: $0 <instrumentationengine|runtimelib>"
    echo "  instrumentationengine - Clone and build PilotExecution project"
    echo "  runtimelib            - Build cloning and Pilot modules"
    exit 1
}

if [ $# -ne 1 ]; then
    usage
fi

run_parallel() {
    local cmd="$1"
    local pids=()

    for node in "${NODES[@]}"; do
        echo "[$node] Starting execution..."
        ssh "$node" "$cmd" &
        pids+=($!)
    done

    local failed=0
    for i in "${!pids[@]}"; do
        if wait "${pids[$i]}"; then
            echo "[${NODES[$i]}] Completed successfully"
        else
            echo "[${NODES[$i]}] Failed with exit code $?"
            failed=1
        fi
    done

    return $failed
}

case "$1" in
    instrumentationengine)
        echo "=== Building Instrumentation Engine ==="
        chmod +x ~/PilotExecution/experiments/ssh_setup/setupssh.sh
        ~/PilotExecution/experiments/ssh_setup/setupssh.sh
        echo "Executed setupssh.sh"


        echo "=== Cloning repository on node1 ==="
        ssh node1 'rm -rf ~/PilotExecution && cd ~ && git clone -b main --recurse-submodules https://github.com/LiftLab-UVA/PilotExecution'
        echo "[node1] Repository cloned successfully"

        echo "=== Setting up scripts on node0 ==="
        ssh node0 'find ~/PilotExecution/experiments -name "*.sh" -exec chmod +x {} \;'
        echo "[node0] Made all .sh scripts executable"

        echo "=== Building on node0 and node1 in parallel ==="
        CMD='cd ~/PilotExecution && mvn clean package -DskipTests'
        run_parallel "$CMD"
        ;;
    runtimelib)
        echo "=== Building Runtime Library ==="
        CMD='cd ~/PilotExecution/cloning && \
mvn clean package -DskipTests && \
mvn install:install-file -Dfile=$HOME/PilotExecution/cloning/target/cloning-1.10.3.jar -DgroupId=uk.robust-it -DartifactId=cloning -Dversion=1.10.3 -Dpackaging=jar && \
cd ../Pilot && \
mvn clean package -DskipTests'
        run_parallel "$CMD"
        ;;
    *)
        echo "Error: Unknown parameter '$1'"
        usage
        ;;
esac

echo "=== All tasks completed ==="