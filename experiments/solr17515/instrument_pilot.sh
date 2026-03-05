#!/bin/bash

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "${SCRIPT_DIR}"

chmod +x instrument_lucene.sh
chmod +x instrument_solr.sh
chmod +x instrument_solrj.sh
chmod +x replace_solr.sh
chmod +x replace_lucene.sh


./instrument_lucene.sh
./instrument_solr.sh
./instrument_solrj.sh

echo "=== Executing replace_solr.sh ==="
./replace_solr.sh

echo "=== Executing replace_lucene.sh ==="
./replace_lucene.sh

echo "Done"