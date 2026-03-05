#!/bin/bash

# Configuration
LOCAL_SOLR_PATH="$HOME/solr_instrumented/sootOutput"
LOCAL_SOLRJ_PATH="$HOME/solrj_instrumented/sootOutput"
REMOTE_BASE_SOLR_PATH="/opt/Solr/solr/build/solr-core/classes/java"
REMOTE_BASE_SOLRJ_PATH="/opt/Solr/solr/build/solr-solrj/classes/java"

# Server list - can add multiple servers
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

echo "=== Starting upload of SOLR and SOLRJ org folders to remote servers ==="

# Check if local SOLR org folder exists
if [ ! -d "${LOCAL_SOLR_PATH}/org" ]; then
    echo -e "${RED}❌ Error: Local path ${LOCAL_SOLR_PATH}/org does not exist${NC}"
    exit 1
fi

# Check if local SOLRJ org folder exists
if [ ! -d "${LOCAL_SOLRJ_PATH}/org" ]; then
    echo -e "${RED}❌ Error: Local path ${LOCAL_SOLRJ_PATH}/org does not exist${NC}"
    exit 1
fi

# Get SOLR local file information
LOCAL_SOLR_FILES=$(find "${LOCAL_SOLR_PATH}/org" -type f | wc -l | tr -d ' ')
LOCAL_SOLR_SIZE=$(du -sb "${LOCAL_SOLR_PATH}/org" 2>/dev/null || du -sk "${LOCAL_SOLR_PATH}/org" | awk '{print $1*1024}')
echo -e "${GREEN}📁 SOLR local org folder information:${NC}"
echo "   File count: ${LOCAL_SOLR_FILES}"
echo "   Size: $((LOCAL_SOLR_SIZE / 1024 / 1024)) MB"
echo ""

# Get SOLRJ local file information
LOCAL_SOLRJ_FILES=$(find "${LOCAL_SOLRJ_PATH}/org" -type f | wc -l | tr -d ' ')
LOCAL_SOLRJ_SIZE=$(du -sb "${LOCAL_SOLRJ_PATH}/org" 2>/dev/null || du -sk "${LOCAL_SOLRJ_PATH}/org" | awk '{print $1*1024}')
echo -e "${GREEN}📁 SOLRJ local org folder information:${NC}"
echo "   File count: ${LOCAL_SOLRJ_FILES}"
echo "   Size: $((LOCAL_SOLRJ_SIZE / 1024 / 1024)) MB"
echo ""

# Create SOLR temporary tar file
TEMP_SOLR_TAR="/tmp/solr_org_upload_$$.tar.gz"
echo "Compressing SOLR local files..."

# macOS compatible tar command, excluding extended attributes
cd "${LOCAL_SOLR_PATH}"
if [[ "$OSTYPE" == "darwin"* ]]; then
    # macOS: Use COPYFILE_DISABLE environment variable to disable extended attributes
    COPYFILE_DISABLE=1 tar czf "${TEMP_SOLR_TAR}" \
        --exclude='._*' \
        --exclude='.DS_Store' \
        --exclude='.AppleDouble' \
        --exclude='.LSOverride' \
        org
else
    # Linux
    tar czf "${TEMP_SOLR_TAR}" \
        --exclude='.DS_Store' \
        org
fi

if [ $? -ne 0 ]; then
    echo -e "${RED}❌ SOLR compression failed${NC}"
    exit 1
fi

TEMP_SOLR_TAR_SIZE=$(stat -f%z "${TEMP_SOLR_TAR}" 2>/dev/null || stat -c%s "${TEMP_SOLR_TAR}" 2>/dev/null)
echo -e "${GREEN}✅ SOLR compression completed (Size: $((TEMP_SOLR_TAR_SIZE / 1024 / 1024)) MB)${NC}"
echo ""

# Create SOLRJ temporary tar file
TEMP_SOLRJ_TAR="/tmp/solrj_org_upload_$$.tar.gz"
echo "Compressing SOLRJ local files..."

cd "${LOCAL_SOLRJ_PATH}"
if [[ "$OSTYPE" == "darwin"* ]]; then
    COPYFILE_DISABLE=1 tar czf "${TEMP_SOLRJ_TAR}" \
        --exclude='._*' \
        --exclude='.DS_Store' \
        --exclude='.AppleDouble' \
        --exclude='.LSOverride' \
        org
else
    tar czf "${TEMP_SOLRJ_TAR}" \
        --exclude='.DS_Store' \
        org
fi

if [ $? -ne 0 ]; then
    echo -e "${RED}❌ SOLRJ compression failed${NC}"
    rm -f "${TEMP_SOLR_TAR}"
    exit 1
fi

TEMP_SOLRJ_TAR_SIZE=$(stat -f%z "${TEMP_SOLRJ_TAR}" 2>/dev/null || stat -c%s "${TEMP_SOLRJ_TAR}" 2>/dev/null)
echo -e "${GREEN}✅ SOLRJ compression completed (Size: $((TEMP_SOLRJ_TAR_SIZE / 1024 / 1024)) MB)${NC}"
echo ""

# Loop through each server
SUCCESSFUL_HOSTS=0
FAILED_HOSTS=0

for REMOTE_HOST in "${REMOTE_HOSTS[@]}"; do
    echo -e "${YELLOW}=== Processing server: ${REMOTE_HOST} ===${NC}"
    SERVER_SUCCESS=true

    # Test SSH connection
    echo "Testing SSH connection..."
    ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=no "${REMOTE_HOST}" "echo 'Connection successful'" > /dev/null 2>&1
    if [ $? -ne 0 ]; then
        echo -e "${RED}❌ Cannot connect to ${REMOTE_HOST}, skipping this server${NC}"
        echo ""
        ((FAILED_HOSTS++))
        continue
    fi

    # ===== Process SOLR =====
    echo -e "${BLUE}>>> Uploading SOLR to ${REMOTE_HOST}...${NC}"

    # Clean SOLR remote directory (no backup)
    echo "Cleaning remote SOLR directory ${REMOTE_BASE_SOLR_PATH} ..."
    ssh -o StrictHostKeyChecking=no "${REMOTE_HOST}" "
        if [ -d '${REMOTE_BASE_SOLR_PATH}' ]; then
            rm -rf ${REMOTE_BASE_SOLR_PATH}/* 2>/dev/null || true
            rm -rf ${REMOTE_BASE_SOLR_PATH}/.* 2>/dev/null || true
            echo 'SOLR directory cleanup completed'
        else
            echo 'SOLR remote directory does not exist, creating directory...'
            mkdir -p '${REMOTE_BASE_SOLR_PATH}'
        fi
    "

    # Upload SOLR and extract
    echo "Starting SOLR upload..."
    if command -v pv &> /dev/null; then
        pv -s "${TEMP_SOLR_TAR_SIZE}" "${TEMP_SOLR_TAR}" | \
            ssh -o StrictHostKeyChecking=no "${REMOTE_HOST}" "
                cd '${REMOTE_BASE_SOLR_PATH}' && \
                tar xzf - 2>/dev/null && \
                echo 'SOLR extraction completed'
            "
        SOLR_UPLOAD_RESULT=$?
    else
        echo "Uploading (SOLR file size: $((TEMP_SOLR_TAR_SIZE / 1024 / 1024)) MB)..."
        cat "${TEMP_SOLR_TAR}" | ssh -o StrictHostKeyChecking=no "${REMOTE_HOST}" "
            cd '${REMOTE_BASE_SOLR_PATH}' && \
            tar xzf - 2>/dev/null && \
            echo 'SOLR extraction completed'
        "
        SOLR_UPLOAD_RESULT=$?
    fi

    if [ $SOLR_UPLOAD_RESULT -eq 0 ]; then
        # Verify SOLR upload
        REMOTE_SOLR_INFO=$(ssh -o StrictHostKeyChecking=no "${REMOTE_HOST}" "
            if [ -d '${REMOTE_BASE_SOLR_PATH}/org' ]; then
                FILE_COUNT=\$(find '${REMOTE_BASE_SOLR_PATH}/org' -type f | wc -l | tr -d ' ')
                DIR_SIZE=\$(du -sh '${REMOTE_BASE_SOLR_PATH}/org' | cut -f1)
                echo \"\${FILE_COUNT}|\${DIR_SIZE}\"
            else
                echo '0|0'
            fi
        " 2>/dev/null)

        REMOTE_SOLR_FILES=$(echo "${REMOTE_SOLR_INFO}" | cut -d'|' -f1)
        REMOTE_SOLR_SIZE=$(echo "${REMOTE_SOLR_INFO}" | cut -d'|' -f2)

        if [ "${REMOTE_SOLR_FILES}" -eq "${LOCAL_SOLR_FILES}" ]; then
            echo -e "${GREEN}✅ SOLR uploaded successfully${NC}"
            echo "   Remote file count: ${REMOTE_SOLR_FILES}"
            echo "   Remote directory size: ${REMOTE_SOLR_SIZE}"

            # Set permissions
            ssh -o StrictHostKeyChecking=no "${REMOTE_HOST}" "chmod -R 755 '${REMOTE_BASE_SOLR_PATH}/org' 2>/dev/null || true"
        else
            echo -e "${YELLOW}⚠️  SOLR upload may be incomplete${NC}"
            echo "   Local file count: ${LOCAL_SOLR_FILES}"
            echo "   Remote file count: ${REMOTE_SOLR_FILES}"
            SERVER_SUCCESS=false
        fi
    else
        echo -e "${RED}❌ SOLR upload failed${NC}"
        SERVER_SUCCESS=false
    fi

    echo ""

    # ===== Process SOLRJ =====
    echo -e "${BLUE}>>> Uploading SOLRJ to ${REMOTE_HOST}...${NC}"

    # Clean SOLRJ remote directory (no backup)
    echo "Cleaning remote SOLRJ directory ${REMOTE_BASE_SOLRJ_PATH} ..."
    ssh -o StrictHostKeyChecking=no "${REMOTE_HOST}" "
        if [ -d '${REMOTE_BASE_SOLRJ_PATH}' ]; then
            rm -rf ${REMOTE_BASE_SOLRJ_PATH}/* 2>/dev/null || true
            rm -rf ${REMOTE_BASE_SOLRJ_PATH}/.* 2>/dev/null || true
            echo 'SOLRJ directory cleanup completed'
        else
            echo 'SOLRJ remote directory does not exist, creating directory...'
            mkdir -p '${REMOTE_BASE_SOLRJ_PATH}'
        fi
    "

    # Upload SOLRJ and extract
    echo "Starting SOLRJ upload..."
    if command -v pv &> /dev/null; then
        pv -s "${TEMP_SOLRJ_TAR_SIZE}" "${TEMP_SOLRJ_TAR}" | \
            ssh -o StrictHostKeyChecking=no "${REMOTE_HOST}" "
                cd '${REMOTE_BASE_SOLRJ_PATH}' && \
                tar xzf - 2>/dev/null && \
                echo 'SOLRJ extraction completed'
            "
        SOLRJ_UPLOAD_RESULT=$?
    else
        echo "Uploading (SOLRJ file size: $((TEMP_SOLRJ_TAR_SIZE / 1024 / 1024)) MB)..."
        cat "${TEMP_SOLRJ_TAR}" | ssh -o StrictHostKeyChecking=no "${REMOTE_HOST}" "
            cd '${REMOTE_BASE_SOLRJ_PATH}' && \
            tar xzf - 2>/dev/null && \
            echo 'SOLRJ extraction completed'
        "
        SOLRJ_UPLOAD_RESULT=$?
    fi

    if [ $SOLRJ_UPLOAD_RESULT -eq 0 ]; then
        # Verify SOLRJ upload
        REMOTE_SOLRJ_INFO=$(ssh -o StrictHostKeyChecking=no "${REMOTE_HOST}" "
            if [ -d '${REMOTE_BASE_SOLRJ_PATH}/org' ]; then
                FILE_COUNT=\$(find '${REMOTE_BASE_SOLRJ_PATH}/org' -type f | wc -l | tr -d ' ')
                DIR_SIZE=\$(du -sh '${REMOTE_BASE_SOLRJ_PATH}/org' | cut -f1)
                echo \"\${FILE_COUNT}|\${DIR_SIZE}\"
            else
                echo '0|0'
            fi
        " 2>/dev/null)

        REMOTE_SOLRJ_FILES=$(echo "${REMOTE_SOLRJ_INFO}" | cut -d'|' -f1)
        REMOTE_SOLRJ_SIZE=$(echo "${REMOTE_SOLRJ_INFO}" | cut -d'|' -f2)

        if [ "${REMOTE_SOLRJ_FILES}" -eq "${LOCAL_SOLRJ_FILES}" ]; then
            echo -e "${GREEN}✅ SOLRJ uploaded successfully${NC}"
            echo "   Remote file count: ${REMOTE_SOLRJ_FILES}"
            echo "   Remote directory size: ${REMOTE_SOLRJ_SIZE}"

            # Set permissions
            ssh -o StrictHostKeyChecking=no "${REMOTE_HOST}" "chmod -R 755 '${REMOTE_BASE_SOLRJ_PATH}/org' 2>/dev/null || true"
        else
            echo -e "${YELLOW}⚠️  SOLRJ upload may be incomplete${NC}"
            echo "   Local file count: ${LOCAL_SOLRJ_FILES}"
            echo "   Remote file count: ${REMOTE_SOLRJ_FILES}"
            SERVER_SUCCESS=false
        fi
    else
        echo -e "${RED}❌ SOLRJ upload failed${NC}"
        SERVER_SUCCESS=false
    fi

    # Count success/failure
    if [ "${SERVER_SUCCESS}" = true ]; then
        echo -e "${GREEN}✅ ${REMOTE_HOST}: Both projects uploaded successfully${NC}"
        ((SUCCESSFUL_HOSTS++))
    else
        echo -e "${RED}❌ ${REMOTE_HOST}: At least one project upload failed${NC}"
        ((FAILED_HOSTS++))
    fi

    echo ""
done

# Clean up temporary files
echo "Cleaning up temporary files..."
rm -f "${TEMP_SOLR_TAR}" "${TEMP_SOLRJ_TAR}"

# Display summary
echo -e "${GREEN}=== Upload task completed ===${NC}"
echo "Total servers processed: ${#REMOTE_HOSTS[@]}"
echo -e "${GREEN}Successful: ${SUCCESSFUL_HOSTS}${NC}"
if [ ${FAILED_HOSTS} -gt 0 ]; then
    echo -e "${RED}Failed: ${FAILED_HOSTS}${NC}"
fi

# Execute ant server on each server
echo ""
echo -e "${YELLOW}=== Running ant server in parallel on all servers ===${NC}"

# Create temporary directory for logs
LOG_DIR="/tmp/ant_server_logs_$$"
mkdir -p "${LOG_DIR}"

# Start all ant server processes in parallel
PIDS=()
for REMOTE_HOST in "${REMOTE_HOSTS[@]}"; do
    LOG_FILE="${LOG_DIR}/${REMOTE_HOST}.log"
    echo -e "${BLUE}Starting ant server on ${REMOTE_HOST} (running in background)...${NC}"

    ssh -o StrictHostKeyChecking=no "${REMOTE_HOST}" "
        cd /opt/Solr/solr && \
        echo 'Current directory: '\$(pwd) && \
        echo 'Starting ant server...' && \
        ant server
    " > "${LOG_FILE}" 2>&1 &

    PID=$!
    PIDS+=($PID)
    echo "  PID: $PID"
done

echo ""
echo -e "${YELLOW}Waiting for all ant server processes to complete...${NC}"

# Wait for all background processes and check results
SUCCESS_COUNT=0
FAIL_COUNT=0
HOST_INDEX=0

for PID in "${PIDS[@]}"; do
    REMOTE_HOST="${REMOTE_HOSTS[$HOST_INDEX]}"
    LOG_FILE="${LOG_DIR}/${REMOTE_HOST}.log"

    # Wait for specific process to complete
    wait $PID
    EXIT_CODE=$?

    if [ $EXIT_CODE -eq 0 ]; then
        echo -e "${GREEN}✅ ${REMOTE_HOST}: ant server execution completed${NC}"
        ((SUCCESS_COUNT++))
    else
        echo -e "${RED}❌ ${REMOTE_HOST}: ant server execution failed (exit code: $EXIT_CODE)${NC}"
        echo "  View log: ${LOG_FILE}"
        ((FAIL_COUNT++))
    fi

    ((HOST_INDEX++))
done

echo ""
echo -e "${GREEN}=== ant server execution summary ===${NC}"
echo "Successful: ${SUCCESS_COUNT}"
if [ ${FAIL_COUNT} -gt 0 ]; then
    echo -e "${RED}Failed: ${FAIL_COUNT}${NC}"
    echo "Log directory: ${LOG_DIR}"
else
    # If all successful, clean up log directory
    rm -rf "${LOG_DIR}"
fi

# Return status code
if [ ${FAILED_HOSTS} -eq 0 ]; then
    exit 0
else
    exit 1
fi