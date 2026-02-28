#!/bin/bash

# Configuration section
LOCAL_PATH="$HOME/lucene_instrumented/sootOutput"
REMOTE_LUCENE_PATH="/opt/Solr/lucene/build/core/classes/java"
REMOTE_WEBAPP_LIB="/opt/Solr/solr/server/solr-webapp/webapp/WEB-INF/lib"

# Server list
REMOTE_HOSTS=(
    "node0"
    "node1"
)

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo "=== Starting Lucene Core instrumented bytecode update ==="

# Check if local org folder exists
if [ ! -d "${LOCAL_PATH}/org" ]; then
    echo -e "${RED}❌ Error: Local path ${LOCAL_PATH}/org does not exist${NC}"
    exit 1
fi

# Get local file information
LOCAL_FILES=$(find "${LOCAL_PATH}/org" -type f | wc -l | tr -d ' ')
LOCAL_SIZE=$(du -sb "${LOCAL_PATH}/org" 2>/dev/null || du -sk "${LOCAL_PATH}/org" | awk '{print $1*1024}')
echo -e "${GREEN}📁 Local org folder info:${NC}"
echo "   File count: ${LOCAL_FILES}"
echo "   Size: $((LOCAL_SIZE / 1024 / 1024)) MB"
echo ""

# Create temporary tar file
TEMP_TAR="/tmp/lucene_org_upload_$$.tar.gz"
echo "Compressing local files..."

# macOS compatible tar command
cd "${LOCAL_PATH}"
if [[ "$OSTYPE" == "darwin"* ]]; then
    COPYFILE_DISABLE=1 tar czf "${TEMP_TAR}" \
        --exclude='._*' \
        --exclude='.DS_Store' \
        --exclude='.AppleDouble' \
        --exclude='.LSOverride' \
        org
else
    tar czf "${TEMP_TAR}" \
        --exclude='.DS_Store' \
        org
fi

if [ $? -ne 0 ]; then
    echo -e "${RED}❌ Compression failed${NC}"
    exit 1
fi

TEMP_TAR_SIZE=$(stat -f%z "${TEMP_TAR}" 2>/dev/null || stat -c%s "${TEMP_TAR}" 2>/dev/null)
echo -e "${GREEN}✅ Compression completed (Size: $((TEMP_TAR_SIZE / 1024 / 1024)) MB)${NC}"
echo ""

# Process each server
SUCCESSFUL_HOSTS=0
FAILED_HOSTS=0

for REMOTE_HOST in "${REMOTE_HOSTS[@]}"; do
    echo -e "${YELLOW}=== Processing server: ${REMOTE_HOST} ===${NC}"

    # Test SSH connection
    echo "Testing SSH connection..."
    ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=no "${REMOTE_HOST}" "echo 'Connection successful'" > /dev/null 2>&1
    if [ $? -ne 0 ]; then
        echo -e "${RED}❌ Cannot connect to ${REMOTE_HOST}, skipping this server${NC}"
        echo ""
        ((FAILED_HOSTS++))
        continue
    fi

    # Backup and clean remote org directory
    echo -e "${BLUE}Processing remote Lucene directory...${NC}"
    ssh -o StrictHostKeyChecking=no "${REMOTE_HOST}" "
        # Backup existing org directory (if exists)
        if [ -d '${REMOTE_LUCENE_PATH}/org' ]; then
            echo 'Deleting existing org directory...'
            rm -rf '${REMOTE_LUCENE_PATH}/org'
        fi

        # Ensure target directory exists
        mkdir -p '${REMOTE_LUCENE_PATH}'
    "

    # Upload and extract
    echo "Uploading instrumented bytecode to ${REMOTE_HOST}..."

    if command -v pv &> /dev/null; then
        pv -s "${TEMP_TAR_SIZE}" "${TEMP_TAR}" | \
            ssh -o StrictHostKeyChecking=no "${REMOTE_HOST}" "
                cd '${REMOTE_LUCENE_PATH}' && \
                tar xzf - 2>/dev/null && \
                echo 'Extraction completed'
            "
        UPLOAD_RESULT=$?
    else
        echo "Uploading (file size: $((TEMP_TAR_SIZE / 1024 / 1024)) MB)..."
        cat "${TEMP_TAR}" | ssh -o StrictHostKeyChecking=no "${REMOTE_HOST}" "
            cd '${REMOTE_LUCENE_PATH}' && \
            tar xzf - 2>/dev/null && \
            echo 'Extraction completed'
        "
        UPLOAD_RESULT=$?
    fi

    if [ $UPLOAD_RESULT -eq 0 ]; then
        echo -e "${GREEN}✅ File upload successful${NC}"

        # Delete old JAR and repackage
        echo -e "${BLUE}Repackaging lucene-core.jar...${NC}"
        ssh -o StrictHostKeyChecking=no "${REMOTE_HOST}" "
            # Change to lucene/core directory
            cd /opt/Solr/lucene/core && \
            echo 'Current directory: '\$(pwd) && \

            # Delete existing JAR file
            echo 'Deleting existing JAR file...' && \
            rm -f /opt/Solr/lucene/build/core/lucene-core-*.jar && \

            # Repackage
            echo 'Executing ant jar-core...' && \
            ant jar-core
        "

        if [ $? -eq 0 ]; then
            echo -e "${GREEN}✅ ${REMOTE_HOST}: lucene-core.jar repackaging successful${NC}"

            # Get the full path of the new JAR
            echo "Finding newly generated JAR file..."
            NEW_JAR_PATH=$(ssh -o StrictHostKeyChecking=no "${REMOTE_HOST}" "ls /opt/Solr/lucene/build/core/lucene-core-*.jar 2>/dev/null | head -1")

            if [ -n "$NEW_JAR_PATH" ]; then
                echo "Found new JAR: $NEW_JAR_PATH"

                # Replace JAR in webapp
                echo -e "${BLUE}Replacing lucene-core.jar in Solr webapp...${NC}"
                ssh -o StrictHostKeyChecking=no "${REMOTE_HOST}" "
                    # Backup old JAR
                    OLD_JAR=\$(ls ${REMOTE_WEBAPP_LIB}/lucene-core-*.jar 2>/dev/null | head -1)
                    if [ -n \"\$OLD_JAR\" ]; then

                        # Delete old lucene-core JAR
                        echo 'Deleting old lucene-core JAR...'
                        rm -f ${REMOTE_WEBAPP_LIB}/lucene-core-*.jar
                    fi

                    # Copy new JAR to webapp
                    echo 'Copying new JAR to webapp...'
                    cp $NEW_JAR_PATH ${REMOTE_WEBAPP_LIB}/

                    # Verify replacement
                    echo 'Verifying replacement result:'
                    ls -la ${REMOTE_WEBAPP_LIB}/lucene-core-*.jar | head -1
                "

                if [ $? -eq 0 ]; then
                    echo -e "${GREEN}✅ ${REMOTE_HOST}: JAR replacement in webapp successful${NC}"
                    ((SUCCESSFUL_HOSTS++))
                else
                    echo -e "${RED}❌ ${REMOTE_HOST}: JAR replacement in webapp failed${NC}"
                    ((FAILED_HOSTS++))
                fi
            else
                echo -e "${RED}❌ Newly generated JAR file not found${NC}"
                ((FAILED_HOSTS++))
            fi
        else
            echo -e "${RED}❌ ${REMOTE_HOST}: Repackaging failed${NC}"
            ((FAILED_HOSTS++))
        fi
    else
        echo -e "${RED}❌ Upload to ${REMOTE_HOST} failed${NC}"
        ((FAILED_HOSTS++))
    fi

    echo ""
done

# Clean up temporary files
echo "Cleaning up temporary files..."
rm -f "${TEMP_TAR}"

# Display summary
echo ""
echo -e "${GREEN}=== Update task completed ===${NC}"
echo "Total servers processed: ${#REMOTE_HOSTS[@]}"
echo -e "${GREEN}Successful: ${SUCCESSFUL_HOSTS}${NC}"
if [ ${FAILED_HOSTS} -gt 0 ]; then
    echo -e "${RED}Failed: ${FAILED_HOSTS}${NC}"
fi
# Return status code
if [ ${FAILED_HOSTS} -eq 0 ]; then
    exit 0
else
    exit 1
fi