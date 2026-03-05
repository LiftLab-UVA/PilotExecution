#!/bin/bash

# Check parameters
if [ $# -lt 1 ]; then
    echo "Usage: $0 <fault_parameter>"
    echo "  fault_parameter will be passed to inject_faults.sh"
    exit 1
fi

FAULT_PARAM="$1"

# Configuration variables - simplified to use only node0 and node1
NODES=("node0" "node1")
SOLR_BIN="/opt/Solr/solr/bin/solr"
COLLECTION="mycollection"

# Check and install jq (Ubuntu)
check_and_install_jq() {
    if ! command -v jq &> /dev/null; then
        echo "jq is not installed, installing for Ubuntu system..."
        sudo apt-get update
        sudo apt-get install -y jq

        # Verify installation
        if command -v jq &> /dev/null; then
            echo "✅ jq installed successfully, version: $(jq --version)"
        else
            echo "❌ jq installation failed, please run manually: sudo apt-get install jq"
            exit 1
        fi
    else
        echo "✅ jq is already installed, version: $(jq --version)"
    fi
}

# Check jq at script start
echo "=== Checking dependencies ==="
check_and_install_jq

echo "=== Step 0: Environment preparation ==="
echo "--- Restarting ZooKeeper ---"
~/PilotExecution/experiments/zookeeper_setup/restart_zookeeper.sh

echo "--- Deploying Solr ---"
~/PilotExecution/experiments/solr17515/managesolr/deploy_solr.sh

echo "--- Restarting Solr ---"
~/PilotExecution/experiments/solr17515/managesolr/restart_solr.sh

echo "=== Step 1: Creating collection (both nodes alive) ==="
curl -s "http://${NODES[0]}:8983/solr/admin/collections?action=CREATE&name=${COLLECTION}&numShards=1&replicationFactor=2&collection.configName=myconfig"
sleep 5

echo -e "\n=== Step 2: Identifying leader and follower ==="
CLUSTER_STATUS=$(curl -s "http://${NODES[0]}:8983/solr/admin/collections?action=CLUSTERSTATUS&collection=${COLLECTION}")

# Parse replica information
for replica in $(echo "$CLUSTER_STATUS" | jq -r ".cluster.collections.${COLLECTION}.shards.shard1.replicas | keys[]"); do
  CORE=$(echo "$CLUSTER_STATUS" | jq -r ".cluster.collections.${COLLECTION}.shards.shard1.replicas.${replica}.core")
  NODE=$(echo "$CLUSTER_STATUS" | jq -r ".cluster.collections.${COLLECTION}.shards.shard1.replicas.${replica}.node_name")
  IS_LEADER=$(echo "$CLUSTER_STATUS" | jq -r ".cluster.collections.${COLLECTION}.shards.shard1.replicas.${replica}.leader // false")

  # Extract hostname from node_name (format: hostname:port_solr)
  NODE_HOST=$(echo "$NODE" | cut -d':' -f1)

  if [ "$IS_LEADER" = "true" ]; then
    LEADER_CORE="$CORE"
    LEADER_NODE="$NODE_HOST"
    echo "Leader: $LEADER_CORE @ $LEADER_NODE"
  else
    FOLLOWER_CORE="$CORE"
    FOLLOWER_NODE="$NODE_HOST"
    FOLLOWER_REPLICA="$replica"
    echo "Follower: $FOLLOWER_CORE @ $FOLLOWER_NODE"
  fi
done

echo -e "\n=== Step 3: Adding initial documents ==="
curl -X POST -H 'Content-Type: application/json' \
  "http://${LEADER_NODE}:8983/solr/${COLLECTION}/update?commit=true" \
  --data-binary '[
    {"id": "1", "title": "Document1"},
    {"id": "2", "title": "Document2"}
  ]'

echo -e "\n=== Step 4: Stopping follower node ==="
echo "Stopping Solr on $FOLLOWER_NODE..."
ssh "$FOLLOWER_NODE" "$SOLR_BIN stop -p 8983"
sleep 5

# Verify follower is down
STATE=$(curl -s "http://${LEADER_NODE}:8983/solr/admin/collections?action=CLUSTERSTATUS&collection=${COLLECTION}" | \
  jq -r ".cluster.collections.${COLLECTION}.shards.shard1.replicas.${FOLLOWER_REPLICA}.state")
echo "Follower state: $STATE"

echo -e "\n=== Step 5: Adding many documents on leader (creating divergence) ==="
# Add 500 documents to exceed PeerSync threshold
for i in {1..5}; do
  echo "Batch $i/5..."
  docs="["
  for j in {1..100}; do
    id=$((i*100+j))
    [ $j -gt 1 ] && docs+=","
    docs+="{\"id\":\"doc_${id}\",\"title\":\"Document${id}\"}"
  done
  docs+="]"

  curl -s -X POST -H 'Content-Type: application/json' \
    "http://${LEADER_NODE}:8983/solr/${COLLECTION}/update?min_rf=1&commit=true" \
    --data-binary "$docs" > /dev/null
done

curl "http://${LEADER_NODE}:8983/solr/${COLLECTION}/update?commit=true"

echo -e "\n=== Step 6: Checking leader document count ==="
LEADER_COUNT=$(curl -s "http://${LEADER_NODE}:8983/solr/${LEADER_CORE}/select?q=*:*&rows=0&distrib=false" | \
  jq -r '.response.numFound')
echo "Leader document count: $LEADER_COUNT"

echo -e "\n=== Step 7: Restarting follower node ==="
ssh "$FOLLOWER_NODE" "$SOLR_BIN start -c -z node0:2181,node1:2181,node2:2181 -p 8983 -h ${FOLLOWER_NODE} -s /opt/SolrData -m 8g"
sleep 10

echo -e "\n=== Step 8: Checking difference before recovery ==="
FOLLOWER_COUNT=$(curl -s "http://${FOLLOWER_NODE}:8983/solr/${FOLLOWER_CORE}/select?q=*:*&rows=0&distrib=false" | \
  jq -r '.response.numFound')
echo "Leader: $LEADER_COUNT, Follower: $FOLLOWER_COUNT"
echo "Difference: $((LEADER_COUNT - FOLLOWER_COUNT)) documents"

# Generate recovery command
RECOVERY_CMD="curl http://${FOLLOWER_NODE}:8983/solr/admin/cores?action=REQUESTRECOVERY&core=${FOLLOWER_CORE}"
echo "$RECOVERY_CMD"

# Write recovery command to /opt/recoverycmd
echo "$RECOVERY_CMD" > /opt/recoverycmd
echo "Recovery command written to /opt/recoverycmd"

echo -e "\n=== Step 9: Injecting fault ==="
~/PilotExecution/experiments/solr17515/inject_fault.sh "$FAULT_PARAM"


#echo -e "\n=== Step 9: Triggering recovery ==="
#curl "http://${FOLLOWER_NODE}:8983/solr/admin/cores?action=REQUESTRECOVERY&core=${FOLLOWER_CORE}"
#
#echo -e "\n=== Step 10: Monitoring recovery progress ==="
#for i in {1..60}; do
#  STATE=$(curl -s "http://${LEADER_NODE}:8983/solr/admin/collections?action=CLUSTERSTATUS&collection=${COLLECTION}" | \
#    jq -r ".cluster.collections.${COLLECTION}.shards.shard1.replicas.${FOLLOWER_REPLICA}.state")
#
#  CURRENT_COUNT=$(curl -s "http://${FOLLOWER_NODE}:8983/solr/${FOLLOWER_CORE}/select?q=*:*&rows=0&distrib=false" | \
#    jq -r '.response.numFound')
#
#  echo -ne "\r[$i/60] State: $STATE, Follower documents: $CURRENT_COUNT/$LEADER_COUNT  "
#
#  if [ "$STATE" = "active" ] && [ "$CURRENT_COUNT" = "$LEADER_COUNT" ]; then
#    echo -e "\n✅ Recovery completed!"
#    break
#  fi
#  sleep 2
#done
#
#echo -e "\n=== Step 11: Final verification ==="
#FINAL_FOLLOWER=$(curl -s "http://${FOLLOWER_NODE}:8983/solr/${FOLLOWER_CORE}/select?q=*:*&rows=0&distrib=false" | \
#  jq -r '.response.numFound')
#echo "Final result - Leader: $LEADER_COUNT, Follower: $FINAL_FOLLOWER"
#
#if [ "$LEADER_COUNT" = "$FINAL_FOLLOWER" ]; then
#  echo "✅ Data synchronization successful!"
#else
#  echo "❌ Data inconsistency!"
#fi