#!/bin/bash

NODES="node0 node1"
MARKER_FILE="/opt/Solr-17515/fault.marker"

if [ "$1" == "bug" ]; then
    echo "Creating fault.marker..."
    for node in $NODES; do
        ssh -o StrictHostKeyChecking=no $node "mkdir -p /opt/Solr-17515 && touch $MARKER_FILE"
        echo "  $node: created"
    done
elif [ "$1" == "fix" ]; then
    echo "Removing fault.marker..."
    for node in $NODES; do
        ssh -o StrictHostKeyChecking=no $node "rm -f $MARKER_FILE"
        echo "  $node: removed"
    done
else
    echo "Usage: $0 [bug|fix]"
    echo "  bug - Create fault.marker"
    echo "  fix - Remove fault.marker"
    exit 1
fi

echo "Done!"