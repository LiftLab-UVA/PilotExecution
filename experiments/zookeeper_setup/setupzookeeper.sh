#!/bin/bash
set -e

# Configuration variables
NODES="node0 node1 node2"
ZOOKEEPER_URL="https://archive.apache.org/dist/zookeeper/zookeeper-3.6.2/apache-zookeeper-3.6.2-bin.tar.gz"
ZOOKEEPER_TAR="apache-zookeeper-3.6.2-bin.tar.gz"
INSTALL_DIR="/opt"
ZOOKEEPER_HOME="/opt/zookeeper"

echo "=========================================="
echo "  ZooKeeper Cluster Deployment Script"
echo "=========================================="

# 1. Download ZooKeeper to local
echo ""
echo "=== Step 1: Download ZooKeeper ==="
if [ ! -f "/tmp/$ZOOKEEPER_TAR" ]; then
    echo "Downloading ZooKeeper..."
    wget -q --show-progress -O "/tmp/$ZOOKEEPER_TAR" "$ZOOKEEPER_URL"
    echo "Download completed!"
else
    echo "ZooKeeper archive already exists, skipping download"
fi

# 2. Create zoo.cfg configuration file
echo ""
echo "=== Step 2: Create configuration file ==="
cat > /tmp/zoo.cfg << 'EOF'
# ZooKeeper configuration file
tickTime=2000
initLimit=10
syncLimit=5
dataDir=/opt/zookeeper/data
dataLogDir=/opt/zookeeper/logs
clientPort=2181

# Cluster configuration
server.1=node0:2888:3888
server.2=node1:2888:3888
server.3=node2:2888:3888
EOF
echo "Configuration file created"

# 3. Distribute files to all nodes
echo ""
echo "=== Step 3: Distribute files to all nodes ==="
for node in $NODES; do
    echo "Copying files to $node..."
    scp -o StrictHostKeyChecking=no "/tmp/$ZOOKEEPER_TAR" "$node:$INSTALL_DIR/"
    scp -o StrictHostKeyChecking=no /tmp/zoo.cfg "$node:/tmp/zoo.cfg"
    echo "  $node file copy completed"
done

# 4. Install and configure ZooKeeper on each node
echo ""
echo "=== Step 4: Install and configure ZooKeeper on each node ==="

# Define node ID mapping
declare -A NODE_IDS
NODE_IDS["node0"]=1
NODE_IDS["node1"]=2
NODE_IDS["node2"]=3

for node in $NODES; do
    SERVER_ID=${NODE_IDS[$node]}
    echo "Configuring $node (Server ID: $SERVER_ID)..."

    ssh -o StrictHostKeyChecking=no "$node" bash -s << REMOTE_SCRIPT
        set -e

        # Extract ZooKeeper
        echo "  Extracting ZooKeeper..."
        cd $INSTALL_DIR
        rm -rf $ZOOKEEPER_HOME
        tar -xzf $ZOOKEEPER_TAR
        mv apache-zookeeper-3.6.2-bin zookeeper

        # Copy configuration file
        echo "  Configuring zoo.cfg..."
        cp /tmp/zoo.cfg $ZOOKEEPER_HOME/conf/zoo.cfg

        # Create data and log directories
        echo "  Creating directories..."
        rm -rf $ZOOKEEPER_HOME/data
        rm -rf $ZOOKEEPER_HOME/logs
        mkdir -p $ZOOKEEPER_HOME/data
        mkdir -p $ZOOKEEPER_HOME/logs

        # Write myid
        echo "  Writing myid ($SERVER_ID)..."
        echo "$SERVER_ID" > $ZOOKEEPER_HOME/data/myid

        # Clean up archive
        rm -f $INSTALL_DIR/$ZOOKEEPER_TAR

        echo "  $node configuration completed!"
REMOTE_SCRIPT
done

# 5. Verify installation
echo ""
echo "=== Step 5: Verify installation ==="
for node in $NODES; do
    echo -n "  $node: "
    MYID=$(ssh -o StrictHostKeyChecking=no "$node" "cat $ZOOKEEPER_HOME/data/myid")
    VERSION=$(ssh -o StrictHostKeyChecking=no "$node" "ls $ZOOKEEPER_HOME/lib/ | grep zookeeper | head -1")
    echo "myid=$MYID, $VERSION"
done

# 6. Clean up temporary files
rm -f /tmp/$ZOOKEEPER_TAR /tmp/zoo.cfg

echo ""
echo "=========================================="
echo "  ZooKeeper Cluster Deployment Completed!"
echo "=========================================="