solrPath=/opt/Solr
patchDir=$HOME/PilotExecution/experiments/solr17515/patch

# The context propagation support for Solr requires zero manual effort. The following patches serve the following purposes:

# This patch integrates the Pilot runtime lib into Solr
patch --fuzz=100 $solrPath/solr/core/ivy.xml $patchDir/ivy.patch

# The following two patches (1)inject the faults to reproduce the issue and
# (2)also ensure that the Pilot execution is stably triggered to make the result reproducible.
cp $patchDir/DefaultSolrCoreState.java $solrPath/solr/core/src/java/org/apache/solr/update/DefaultSolrCoreState.java
cp $patchDir/RecoveryStrategy.java $solrPath/solr/core/src/java/org/apache/solr/cloud/RecoveryStrategy.java

# The following patch shows the transformation for the CLI entry point (#Note: merge fully automatic instrumentation code for entry point into current branch)
cp $patchDir/CoreAdminOperation.java $solrPath/solr/core/src/java/org/apache/solr/handler/admin/CoreAdminOperation.java

# Unlike caching intermediate function results, we still need to manually modify the code in the Solr case to cache IO results for optimal performance.
cp $patchDir/IndexFetcher.java $solrPath/solr/core/src/java/org/apache/solr/handler/IndexFetcher.java