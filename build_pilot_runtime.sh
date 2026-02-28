cd cloning
mvn clean package -DskipTests
mvn install:install-file -Dfile=$HOME/PilotExecution/cloning/target/cloning-1.10.3.jar -DgroupId=uk.robust-it -DartifactId=cloning -Dversion=1.10.3 -Dpackaging=jar
cd ../Pilot
mvn clean package -DskipTests


