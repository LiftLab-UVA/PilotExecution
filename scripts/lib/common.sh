function run_recovery_checker() {
  ${rc} -o ${out_dir} "$@"
  if [ $? -ne 0 ]; then
    exit $?
  fi
}

function check_dir() {
  if [ ! -d $1 ]; then
    echo "Could not find directory $1"
    exit 1
  fi
}

scripts_common_dir=$(cd "$(dirname "${BASH_SOURCE-$0}")"; pwd)
root_dir=$(dirname $(dirname "${scripts_common_dir}"))  # root is ../..
bin_dir="${root_dir}/bin"
out_dir=${root_dir}/sootOutput
test_dir=${root_dir}/test
lib_dir=${root_dir}/lib
rc=${bin_dir}/recoverychecker.sh

check_dir ${bin_dir}
check_dir ${lib_dir}

if [ ! -x ${rc} ]; then
  echo "Could not find ${rc}"
  exit 1
fi