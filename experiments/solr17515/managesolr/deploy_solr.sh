#!/bin/bash

HOSTS=("node0" "node1")

echo "=== Solr Configuration Initialization Script ==="

echo "Step 1: Replacing configuration files..."
for host in "${HOSTS[@]}"; do
    ssh -o StrictHostKeyChecking=no "${host}" "
        mkdir -p /opt/Solr/conf
        rm -f /opt/Solr/conf/schema.xml /opt/Solr/conf/solrconfig.xml
        cp ~/PilotExecution/experiments/solr17515/conf/schema.xml /opt/Solr/conf/
        cp ~/PilotExecution/experiments/solr17515/conf/solrconfig.xml /opt/Solr/conf/
    " &
done
wait
echo "✅ Configuration files replaced"

echo "Step 2: Initializing data directories..."
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
echo "✅ Initialization complete"

echo "=== Configuration Initialization Complete ==="