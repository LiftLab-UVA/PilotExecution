#!/bin/bash

recoverychecker_dir=$(cd "$(dirname "${BASH_SOURCE-$0}")"; pwd)

echo "Setting tool root dir: " "${recoverychecker_dir}"

if [[ -z "$JAVA_HOME" ]]; then
  export JAVA_HOME=$(readlink -f /usr/bin/javac | sed "s:/bin/javac::")
  echo "Warning: JAVA_HOME env is not set, inferring it to be $JAVA_HOME"
fi


if [ -f "${recoverychecker_dir}/run_engine.sh" ]; then
    echo "The tool is running under recoverychecker dir"
else
    echo "[ERROR] cannot detect run_engine.sh under root dir, please run this script under PILOT root dir"
    echo "Abort."
    exit
fi


recoverychecker_lib="${recoverychecker_dir}/target/RecoveryChecker-1.0-SNAPSHOT-jar-with-dependencies.jar"
log4j_config="${recoverychecker_dir}/conf/log4j.properties"

banner() {
  echo -e ""
  echo " ▄▄▄▄▄▄▄▄▄▄▄  ▄▄▄▄▄▄▄▄▄▄▄  ▄            ▄▄▄▄▄▄▄▄▄▄▄  ▄▄▄▄▄▄▄▄▄▄▄ "
  echo "▐░░░░░░░░░░░▌▐░░░░░░░░░░░▌▐░▌          ▐░░░░░░░░░░░▌▐░░░░░░░░░░░▌"
  echo "▐░█▀▀▀▀▀▀▀█░▌ ▀▀▀▀█░█▀▀▀▀ ▐░▌          ▐░█▀▀▀▀▀▀▀█░▌ ▀▀▀▀█░█▀▀▀▀ "
  echo "▐░▌       ▐░▌     ▐░▌     ▐░▌          ▐░▌       ▐░▌     ▐░▌     "
  echo "▐░█▄▄▄▄▄▄▄█░▌     ▐░▌     ▐░▌          ▐░▌       ▐░▌     ▐░▌     "
  echo "▐░░░░░░░░░░░▌     ▐░▌     ▐░▌          ▐░▌       ▐░▌     ▐░▌     "
  echo "▐░█▀▀▀▀▀▀▀▀▀      ▐░▌     ▐░▌          ▐░▌       ▐░▌     ▐░▌     "
  echo "▐░▌               ▐░▌     ▐░▌          ▐░▌       ▐░▌     ▐░▌     "
  echo "▐░▌           ▄▄▄▄█░█▄▄▄▄ ▐░█▄▄▄▄▄▄▄▄▄ ▐░█▄▄▄▄▄▄▄█░▌     ▐░▌     "
  echo "▐░▌          ▐░░░░░░░░░░░▌▐░░░░░░░░░░░▌▐░░░░░░░░░░░▌     ▐░▌     "
  echo " ▀            ▀▀▀▀▀▀▀▀▀▀▀  ▀▀▀▀▀▀▀▀▀▀▀  ▀▀▀▀▀▀▀▀▀▀▀       ▀      "
  echo -e ""
}

usage (){
  echo -e "PILOT Engine Navigator: ./run_engine.sh [command] [args...]"
  echo -e ""
  echo -e "\t command:"
  echo -e ""
  echo -e "\t help              \t\t\t get help information"
}

compile_target () {
    cd ${system_dir_path} || return
    eval ${compile_cmd}
}

timing ()
{
    echo "Start $2 for $3"

    SECONDS=0
    $1
    duration=$SECONDS
    dt=$(date '+%d/%m/%Y %H:%M:%S');
    echo "$dt"
    echo "[Profiler] $2 for $3 spent ${duration} seconds"
}

transform_recovery_code() {
  cd ${recoverychecker_dir} || return

#  marker=${system_dir_path}"/recoverychecker.mark"
#  echo "marker: $marker"
#  if [[ $if_ignore_marker != "-i" ]]; then
#        if test -f "$marker"; then
#         echo "$marker exists. Already transformed before, exit"
#         echo "If you think this is a mistake, you should force to clean the marker by delete it"
#         exit
#        fi
#  fi

  echo "transforming recovery code for system dir: ${system_dir_path} with classes path: ${system_classes_path}"
  /bin/bash scripts/instrument/transform.sh "${system_classes_path}" "${conf_file_realpath}"


}

analyze_code(){
  cd ${recoverychecker_dir} || return
  scripts/instrument/recovery_pattern_finder.sh "${system_classes_path}" "${conf_file_realpath}"
}

banner

if [[ $1 == "help" ]]
then
    usage
    exit 0
fi

if [[ $1 == "compile" ]]
then
    echo "building the recovery checker tool now"
    mvn clean package -DskipTests
    exit 0
fi

conf_file_path=$2
conf_file_realpath=$(realpath ${conf_file_path})
source ${conf_file_path}

# Remove trailing slash from dir
echo "system_dir_path: $system_dir_path"
echo "system_classes_path: $system_classes_path"
echo "java_class_path: $java_class_path"
export SOOT_CLASSPATH="${java_class_path}"
case $system_dir_path in
    *[!/]*/) system_dir_path=${system_dir_path%"${system_dir_path##*[!/]}"};;
    *[/]) system_dir_path="/";;
esac

full_class_path=${recoverychecker_lib}:${java_class_path}

if [[ $1 == "compile_target" ]]
then
    timing compile_target "compile_target" ${system_dir_path}
elif [[ $1 == "transform" ]]
then
    echo "transforming recovery code"
    if_ignore_marker=$3
    timing transform_recovery_code "transform_recovery_code" ${system_dir_path}
elif [[ $1 == "analyze" ]]
then
  echo "perform static analysis"
  timing
fi


