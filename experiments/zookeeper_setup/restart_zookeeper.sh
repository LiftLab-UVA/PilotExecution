#!/bin/bash

# Define variables
HOSTS=(
  "node0"
  "node1"
  "node2"
)

# Display script start message
echo "=== ZooKeeper Cluster Cleanup Script ==="
echo "Will clean ZooKeeper data on the following nodes:"
for host in "${HOSTS[@]}"; do
  echo "- $host"
done
echo ""

# Cleanup function
clean_zookeeper() {
  local host=$1
  echo "======================================"
  echo "Connecting to $host and performing ZooKeeper cleanup"
  echo "======================================"

  # Create remote execution command
  SSH_COMMAND='
    ZK_HOME="/opt/zookeeper"
    ZK_CMD="$ZK_HOME/bin/zkServer.sh"
    ZK_CFG="$ZK_HOME/conf/zoo.cfg"

    # Find data directory from config file
    if [ -f "$ZK_CFG" ]; then
        DATA_DIR=$(grep "^dataDir=" "$ZK_CFG" | cut -d= -f2)
        LOGS_DIR=$(grep "^dataLogDir=" "$ZK_CFG" | cut -d= -f2)
    else
        echo "ZooKeeper config file not found: $ZK_CFG"
        exit 1
    fi

    echo "ZooKeeper home directory: $ZK_HOME"
    echo "ZooKeeper data directory: $DATA_DIR"
    if [ -n "$LOGS_DIR" ]; then
        echo "ZooKeeper log directory: $LOGS_DIR"
    fi

    # Stop ZooKeeper service
    echo "Stopping ZooKeeper service..."
    $ZK_CMD stop

    # Wait for ZooKeeper to fully stop
    echo "Waiting for ZooKeeper to fully stop..."
    sleep 5

    # Find ZooKeeper Java process using ps (exclude grep and ssh related processes)
    echo "Searching for ZooKeeper processes..."
    ZOOKEEPER_PIDS=$(ps -ef | grep "org.apache.zookeeper" | grep -v grep | awk '\''{print $2}'\'')

    if [ -n "$ZOOKEEPER_PIDS" ]; then
        echo "Found ZooKeeper processes, PID list: $ZOOKEEPER_PIDS"

        # Try to terminate processes gracefully
        echo "Attempting graceful termination..."
        for pid in $ZOOKEEPER_PIDS; do
            echo "Terminating process PID: $pid"
            sudo kill $pid 2>/dev/null
        done

        # Wait for processes to terminate
        echo "Waiting for processes to terminate..."
        sleep 5

        # Check if any processes are still running
        REMAINING_PIDS=$(ps -ef | grep "org.apache.zookeeper" | grep -v grep | awk '\''{print $2}'\'')
        if [ -n "$REMAINING_PIDS" ]; then
            echo "ZooKeeper processes still running, forcing termination..."
            for pid in $REMAINING_PIDS; do
                echo "Force killing process PID: $pid"
                sudo kill -9 $pid 2>/dev/null
            done
            sleep 2

            # Final check
            FINAL_CHECK=$(ps -ef | grep "org.apache.zookeeper" | grep -v grep)
            if [ -n "$FINAL_CHECK" ]; then
                echo "Warning: Some ZooKeeper processes could not be terminated:"
                echo "$FINAL_CHECK"
            else
                echo "All ZooKeeper processes terminated"
            fi
        else
            echo "All ZooKeeper processes terminated"
        fi
    else
        echo "No running ZooKeeper processes found"
    fi

    # Delete ZooKeeper data
    echo "Cleaning ZooKeeper data..."
    if [ -d "$DATA_DIR" ]; then
        # Backup myid file (if exists)
        if [ -f "$DATA_DIR/myid" ]; then
            MYID=$(cat "$DATA_DIR/myid")
            echo "Backed up myid file, node ID: $MYID"
        fi

        # Delete all contents in data directory
        echo "Deleting data directory contents: $DATA_DIR/*"
        rm -rf "$DATA_DIR"/*

        # Recreate myid file
        if [ -n "$MYID" ]; then
            echo "Recreating myid file..."
            echo "$MYID" > "$DATA_DIR/myid"
            echo "myid file recreated, content: $MYID"
        fi
    else
        echo "Warning: Data directory does not exist: $DATA_DIR"
        mkdir -p "$DATA_DIR"
        echo "Created data directory: $DATA_DIR"
    fi

    # Clean log directory
    if [ -n "$LOGS_DIR" ] && [ -d "$LOGS_DIR" ]; then
        echo "Cleaning transaction log directory: $LOGS_DIR/*"
        rm -rf "$LOGS_DIR"/*
        echo "Transaction log directory cleaned"
    else
        echo "No separate log directory configured or directory does not exist"
    fi

    echo "ZooKeeper environment cleanup completed"
  '

  # Use SSH to connect to remote host and execute command
  echo "SSH connecting to $host to perform cleanup..."
  ssh -o StrictHostKeyChecking=no "$host" "$SSH_COMMAND"

  # Check command execution status
  if [ $? -eq 0 ]; then
    echo "✅ ZooKeeper environment cleanup successful on node $host"
  else
    echo "❌ Error: Failed to complete ZooKeeper environment cleanup on node $host"
  fi

  echo ""
}

# Clean all nodes in parallel
echo "=== Starting parallel cleanup on all nodes ==="
for host in "${HOSTS[@]}"; do
  clean_zookeeper "$host" &
done

# Wait for all cleanup tasks to complete
wait

echo "ZooKeeper environment cleanup completed on all nodes"
echo "=== Starting parallel restart of all ZooKeeper nodes ==="

# Restart all ZooKeeper nodes in parallel
for host in "${HOSTS[@]}"; do
  {
    echo "Starting ZooKeeper service on $host..."
    ssh -o StrictHostKeyChecking=no "$host" "/opt/zookeeper/bin/zkServer.sh start"

    if [ $? -eq 0 ]; then
      echo "✅ ZooKeeper service started successfully on node $host"
    else
      echo "❌ Error: Failed to start ZooKeeper service on node $host"
    fi
  } &
done

# Wait for all restart tasks to complete
wait

echo "=== ZooKeeper Cluster Restart Complete ==="
echo "Cluster cleanup and restart successful!"