#!/bin/bash

HOSTS=("node0" "node1")

echo "=== Solr Build and Deploy Script ==="

echo "Step 1: Preparing environment on all nodes..."
for host in "${HOSTS[@]}"; do
    ssh -o StrictHostKeyChecking=no "${host}" "
        sudo chmod -R 777 /opt
        rm -rf /opt/Solr
    " &
done
wait
echo "✅ Environment preparation completed"

echo "Step 2: Downloading and extracting Solr on all nodes..."
for host in "${HOSTS[@]}"; do
    ssh -o StrictHostKeyChecking=no "${host}" "
        cd /opt
        rm -rf solr-8.11.4-src.tgz
        curl -L -O https://downloads.apache.org/lucene/solr/8.11.4/solr-8.11.4-src.tgz
        tar -xzf solr-8.11.4-src.tgz
        mv solr-8.11.4 Solr
        rm -f solr-8.11.4-src.tgz
    " &
done
wait
echo "✅ Solr download and extraction completed"

echo "Step 3: Applying patches on all nodes..."
for host in "${HOSTS[@]}"; do
    ssh -o StrictHostKeyChecking=no "${host}" "
        chmod +x ~/PilotExecution/experiments/solr17515/patch/apply_patch.sh
        cd ~/PilotExecution/experiments/solr17515/patch
        ./apply_patch.sh
    " &
done
wait
echo "✅ Patches applied successfully"

echo "Step 4: Initializing Ivy on all nodes..."
for host in "${HOSTS[@]}"; do
    ssh -o StrictHostKeyChecking=no "${host}" "
        cd /opt/Solr
        ant ivy-bootstrap
    " &
done
wait
echo "✅ Ivy initialization completed"

echo "Step 5: Installing Pilot JAR on all nodes..."
for host in "${HOSTS[@]}"; do
    ssh -o StrictHostKeyChecking=no "${host}" "
        rm -rf ~/.ivy2/local/org.pilot
        rm -rf ~/.ivy2/cache
        rm -rf ~/.m2/repository/org/pilot/pilot-util
        mvn install:install-file -Dfile=\$HOME/PilotExecution/Pilot/target/Pilot-1.0-SNAPSHOT.jar -DgroupId=org.pilot -DartifactId=pilot-util -Dversion=1.0 -Dpackaging=jar

        mkdir -p ~/.ivy2/local/org.pilot/pilot-util/1.0/jars/
        rm -rf ~/.ivy2/local/org.pilot/pilot-util/1.0/jars/pilot-util.jar
        cp ~/.m2/repository/org/pilot/pilot-util/1.0/pilot-util-1.0.jar ~/.ivy2/local/org.pilot/pilot-util/1.0/jars/pilot-util.jar

        rm -rf /opt/Solr/solr/core/lib/Pilot-1.0-SNAPSHOT.jar
        rm -rf /opt/Solr/solr/core/lib/pilot-util-1.0.jar
        rm -rf /opt/Solr/solr/solrj/lib/Pilot-1.0-SNAPSHOT.jar
        rm -rf /opt/Solr/solr/solrj/lib/pilot-util-1.0.jar
        cp /opt/Pilot-1.0-SNAPSHOT.jar /opt/Solr/solr/core/lib/
        cp /opt/Pilot-1.0-SNAPSHOT.jar /opt/Solr/solr/solrj/lib/

        rm -rf /opt/Solr/solr/server/lib/ext/Pilot-1.0-SNAPSHOT.jar
        cp /opt/Pilot-1.0-SNAPSHOT.jar /opt/Solr/solr/server/lib/ext/

        mkdir -p ~/.ivy2/local/org.pilot/pilot-util/1.0/ivys/
        echo '<?xml version=\"1.0\"?>
<ivy-module version=\"2.0\">
  <info organisation=\"org.pilot\" module=\"pilot-util\" revision=\"1.0\"/>
</ivy-module>' > ~/.ivy2/local/org.pilot/pilot-util/1.0/ivys/ivy.xml
    " &
done
wait
echo "✅ Pilot JAR installation completed"

echo "Step 6: Building Solr on all nodes..."
for host in "${HOSTS[@]}"; do
    ssh -o StrictHostKeyChecking=no "${host}" "
        cd /opt/Solr
        ant clean && ant compile
        cd solr/
        ant server
    " &
done
wait
echo "✅ Solr build completed"


echo "=== Build and Deploy Completed ==="