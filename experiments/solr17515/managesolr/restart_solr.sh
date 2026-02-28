#!/bin/bash

HOSTS=("node0" "node1")
INTERNAL_IPS=("10.10.1.1" "10.10.1.2")
SOLR_BIN="/opt/Solr/solr/bin/solr"
SOLR_DATA="/opt/SolrData"
SOLR_PORT="8983"
SOLR_MEMORY="8g"
SOLR_CONFIG_DIR="/opt/Solr/conf"
CONFIG_NAME="myconfig"
ZK_ENSEMBLE="10.10.1.1:2181,10.10.1.2:2181,10.10.1.3:2181"

echo "=== Stopping Solr Cluster ==="
for host in "${HOSTS[@]}"; do
    {
        echo "Stopping $host..."
        ssh -o StrictHostKeyChecking=no "$host" "
            $SOLR_BIN stop -all 2>/dev/null
            sleep 2
            PID=\$(lsof -t -i:$SOLR_PORT 2>/dev/null)
            if [ -n \"\$PID\" ]; then
                echo \"Force killing process: \$PID\"
                kill -9 \$PID 2>/dev/null
            fi
            find $SOLR_DATA -type f ! -name '*.xml' -delete 2>/dev/null
            find $SOLR_DATA -type d -empty -delete 2>/dev/null
            rm -rf /opt/ShadowDirectory /opt/ShadowAppendLog
        "
        echo "✅ $host stopped"
    } &
done
wait

for host in "${HOSTS[@]}"; do
    ssh -o StrictHostKeyChecking=no "${host}" "
        sudo rm -rf /opt/SolrData /opt/SolrDataPilot /opt/ShadowDirectory /opt/ShadowAppendLog
        sudo mkdir -p /opt/SolrData /opt/SolrDataPilot
        sudo chmod -R 777 /opt
        sudo chmod +x /opt/Solr/solr/bin/solr
        cp /opt/Solr/solr/server/solr/solr.xml /opt/SolrData/
    " &
done
wait

echo ""
echo "=== Starting Solr Cluster ==="
echo "ZooKeeper: $ZK_ENSEMBLE"
for i in "${!HOSTS[@]}"; do
    {
        host="${HOSTS[$i]}"
        ip="${INTERNAL_IPS[$i]}"
        echo "Starting $host ($ip)..."
        ssh -o StrictHostKeyChecking=no "$host" "
            $SOLR_BIN start -c -z $ZK_ENSEMBLE -p $SOLR_PORT -h $ip -s $SOLR_DATA -m $SOLR_MEMORY
        "
        echo "✅ $host started"
    } &
done
wait

echo "Waiting for cluster to stabilize..."
sleep 15

echo "Uploading configuration to ZooKeeper..."
ssh -o StrictHostKeyChecking=no "${HOSTS[0]}" "
    $SOLR_BIN zk upconfig -n $CONFIG_NAME -d $SOLR_CONFIG_DIR -z $ZK_ENSEMBLE
"

echo ""
echo "=== Solr Cluster Status ==="
for host in "${HOSTS[@]}"; do
    echo "--- $host ---"
    ssh -o StrictHostKeyChecking=no "$host" "$SOLR_BIN status" 2>/dev/null
done

echo "=== Done ==="