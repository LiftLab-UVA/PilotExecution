#!/bin/bash

NODES="node0 node1"
MARKER_FILE="/opt/Solr-17515/fault.marker"

echo "simulate fix conf.."
for node in $NODES; do
    ssh -o StrictHostKeyChecking=no $node "rm -f $MARKER_FILE"
    echo "  $node: removed"
done

echo "Done!"