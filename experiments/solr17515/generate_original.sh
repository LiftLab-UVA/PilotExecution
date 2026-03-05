#!/bin/bash

# Configuration parameters
SERVER="node0"
SOLR_PATH="/opt/Solr"
TARGET_DIR="originalClass"

echo "Configuration:"
echo "  Server: ${SERVER}"
echo "  Solr Path: ${SOLR_PATH}"
echo "  Target Directory: ${TARGET_DIR}"
echo ""
echo "Connecting..."

# SSH connection and execute commands
ssh -o StrictHostKeyChecking=no "${SERVER}" << EOF
    echo "Connected successfully!"

    # Set variables
    SOLR_PATH="${SOLR_PATH}"
    TARGET_DIR="${TARGET_DIR}"

    # Change to Solr directory
    cd "\${SOLR_PATH}"
    echo "Changed to directory: \$(pwd)"

    rm -rf "\${SOLR_PATH}/\${TARGET_DIR}"

    # Create lucene/classes directory and copy files
    echo "Creating \${SOLR_PATH}/\${TARGET_DIR}/lucene/classes..."
    mkdir -p "\${SOLR_PATH}/\${TARGET_DIR}/lucene/classes"
    echo "Copying lucene classes..."
    cp -r "\${SOLR_PATH}/lucene/build/core/classes/java/org" "\${SOLR_PATH}/\${TARGET_DIR}/lucene/classes/"

    # Create solrcore/classes directory and copy files
    echo "Creating \${SOLR_PATH}/\${TARGET_DIR}/solrcore/classes..."
    mkdir -p "\${SOLR_PATH}/\${TARGET_DIR}/solrcore/classes"
    echo "Copying solrcore classes..."
    cp -r "\${SOLR_PATH}/solr/build/solr-core/classes/java/org" "\${SOLR_PATH}/\${TARGET_DIR}/solrcore/classes/"

    # Create solrj/classes directory and copy files
    echo "Creating \${SOLR_PATH}/\${TARGET_DIR}/solrj/classes..."
    mkdir -p "\${SOLR_PATH}/\${TARGET_DIR}/solrj/classes"
    echo "Copying solrj classes..."
    cp -r "\${SOLR_PATH}/solr/build/solr-solrj/classes/java/org" "\${SOLR_PATH}/\${TARGET_DIR}/solrj/classes/"

    echo "All operations completed successfully!"
EOF

echo "Script execution finished."